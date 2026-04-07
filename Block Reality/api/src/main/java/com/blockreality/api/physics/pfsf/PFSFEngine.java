package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.FailureType;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 勢場導流物理引擎 — 總入口。
 *
 * <p>協調 Vulkan Compute Pipelines、Jacobi 迭代、V-Cycle 多重網格、
 * 斷裂偵測和 SCA 連鎖崩塌。每 Server Tick 呼叫 {@link #onServerTick}。</p>
 *
 * <p>GPU dispatch 邏輯委派至：</p>
 * <ul>
 *   <li>{@link PFSFPipelineFactory} — Pipeline 建立/銷毀</li>
 *   <li>{@link PFSFDataBuilder} — Source/Conductivity 資料建構</li>
 *   <li>{@link PFSFVCycleRecorder} — Jacobi + V-Cycle GPU 錄製</li>
 *   <li>{@link PFSFFailureRecorder} — 失效偵測 + 壓縮 readback</li>
 *   <li>{@link PFSFBufferManager} — Buffer 生命週期管理</li>
 *   <li>{@link PFSFStressExtractor} — GPU→CPU 應力場讀取</li>
 * </ul>
 *
 * 參考：PFSF 手冊 §5
 */
public final class PFSFEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    // ─── State ───
    private static boolean initialized = false;
    private static boolean available = false;

    // ─── Descriptor Pool（按需重置取代固定 20 tick 間隔）───
    private static DescriptorPoolManager descriptorPoolMgr;

    // ─── VRAM 感知組件 ───
    private static final IslandBufferEvictor evictor = new IslandBufferEvictor();
    private static long tickCounter = 0;

    // ─── Material lookup (set by Mod initialization) ───
    private static Function<BlockPos, RMaterial> materialLookup;
    private static Function<BlockPos, Boolean> anchorLookup;
    private static Function<BlockPos, Float> fillRatioLookup;
    // v2.1: 固化時間效應 + 風向
    private static Function<BlockPos, Float> curingLookup = null;
    private static net.minecraft.world.phys.Vec3 currentWindVec = null;

    /** M10: 同步 tick 計數器（每 island） */
    private static final ConcurrentHashMap<Integer, Integer> syncCounters = new ConcurrentHashMap<>();

    private PFSFEngine() {}

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!VulkanComputeContext.isAvailable()) {
            LOGGER.warn("[PFSF] VulkanComputeContext not available, engine disabled");
            available = false;
            return;
        }

        try {
            PFSFPipelineFactory.createAll();
            long pool = VulkanComputeContext.createDescriptorPool(2048, 8192);
            descriptorPoolMgr = new DescriptorPoolManager(pool, 2048);
            available = true;

            VramBudgetManager budgetMgr = VulkanComputeContext.getVramBudgetManager();
            LOGGER.info("[PFSF] Engine initialized (VRAM budget: {}MB, detected: {}MB)",
                    budgetMgr.getTotalBudget() / (1024 * 1024),
                    budgetMgr.getDetectedDeviceLocalBytes() / (1024 * 1024));
        } catch (Throwable e) {
            LOGGER.error("[PFSF] Engine init failed: {}", e.getMessage());
            available = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main Tick Loop (§5.2)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 每 Server Tick 的入口 — 非同步 Triple-Buffered 管線。
     *
     * <pre>
     * Tick N: [CPU 準備 sparse updates] [GPU 計算 N-1] [CPU 處理 N-2 結果]
     * </pre>
     */
    public static void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;
        tickCounter++;

        // ─── Phase 1: 回收完成的 GPU 結果（非阻塞） ───
        PFSFAsyncCompute.pollCompleted();

        // 按需重置 descriptor pool（取代固定 20 tick 間隔）
        descriptorPoolMgr.tickResetIfNeeded();
        long descriptorPool = descriptorPoolMgr.getPool();

        // 按需驅逐閒置 island（只在 VRAM 壓力 > 90% 時執行）
        VramBudgetManager budgetMgr = VulkanComputeContext.getVramBudgetManager();
        evictor.evictIfNeeded(tickCounter, budgetMgr, PFSFBufferManager.buffers);

        long startTime = System.nanoTime();

        // ─── Batch 收集（Ping-Pong 多 island 並行）───
        List<PFSFAsyncCompute.ComputeFrame> batch = new ArrayList<>();
        List<java.util.function.Consumer<Void>> callbacks = new ArrayList<>();

        // Iterate dirty islands
        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(currentEpoch).entrySet()) {

            // Tick budget check
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= BRConfig.getPFSFTickBudgetMs()) break;

            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            // M3-fix: 邊界防護
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;

            // 記錄 island 存取（evictor 用）
            evictor.touchIsland(islandId, tickCounter);

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            if (buf == null) continue; // VRAM 不足，island 被拒絕

            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            // ─── Phase 2: 取得非同步 frame ───
            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) break; // 所有 frame 都在飛行中
            frame.islandId = islandId;

            // Phase 3: 稀疏更新或全量重建
            if (sparse.hasPendingUpdates()) {
                List<PFSFSparseUpdate.VoxelUpdate> updates = sparse.drainUpdates();
                if (updates == null) {
                    PFSFDataBuilder.updateSourceAndConductivity(buf, island, level,
                            materialLookup, anchorLookup, fillRatioLookup,
                            curingLookup, currentWindVec);
                    buf.markClean();
                } else if (!updates.isEmpty()) {
                    int count = sparse.packUpdates(updates);
                    PFSFFailureRecorder.recordSparseScatter(frame.cmdBuf, buf, sparse, count, descriptorPool);
                    buf.markClean();
                }
            }

            // ─── Phase 4: RBGS 迭代 + W-Cycle ───
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                float change = Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
                if (change < PFSFScheduler.MACRO_BLOCK_CONVERGENCE_THRESHOLD) {
                    StructureIslandRegistry.markProcessed(islandId);
                    continue;
                }
            }

            boolean hasCollapse = false;
            int baseSteps = PFSFScheduler.recommendSteps(buf, false, hasCollapse);

            // VRAM 感知步數調整
            ComputeRangePolicy.ComputeConfig computeConfig =
                    ComputeRangePolicy.decide(budgetMgr, buf.getN());
            int steps = computeConfig != null
                    ? ComputeRangePolicy.adjustSteps(baseSteps, computeConfig)
                    : baseSteps;

            for (int k = 0; k < steps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    PFSFVCycleRecorder.recordVCycle(frame.cmdBuf, buf, descriptorPool);
                } else {
                    PFSFVCycleRecorder.recordRBGSStep(frame.cmdBuf, buf, descriptorPool);
                    buf.chebyshevIter++;
                }
            }

            // ─── Phase 4.5: Phase-Field Evolution ───
            if (steps > 0 && buf.getDFieldBuf() != 0) {
                recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
            }

            // ─── Phase 5: failure scan + compact readback ───
            if (steps > 0) {
                PFSFFailureRecorder.recordFailureScan(frame.cmdBuf, buf, descriptorPool);
                PFSFFailureRecorder.recordFailureCompact(frame.cmdBuf, buf, frame, descriptorPool);
                PFSFFailureRecorder.recordPhiMaxReduction(frame.cmdBuf, buf, frame);
            }

            // ─── Phase 6: 收集到 batch ───
            buf.retain();
            final PFSFIslandBuffer finalBuf = buf;
            batch.add(frame);
            callbacks.add(v -> {
                try {
                    processCompletedFrame(frame, finalBuf, level);
                } finally {
                    finalBuf.release();
                }
            });

            StructureIslandRegistry.markProcessed(islandId);

            // 最多收集 3 個 frame 一次 batch submit
            if (batch.size() >= 3) break;
        }

        // ─── Batch Submit（多 island GPU 並行）───
        if (!batch.isEmpty()) {
            PFSFAsyncCompute.submitBatch(batch, callbacks);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU Result Processing
    // ═══════════════════════════════════════════════════════════════

    /**
     * 處理 GPU 完成的計算結果（在主線程上的回調）。
     */
    private static void processCompletedFrame(PFSFAsyncCompute.ComputeFrame frame,
                                                PFSFIslandBuffer buf, ServerLevel level) {
        if (frame.readbackStagingBuf == null) return;

        // 讀取壓縮後的 failure 結果
        java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(
                frame.readbackStagingBuf[1], frame.readbackStagingSize);
        int failCount = mapped.getInt(0);

        if (failCount > 0) {
            failCount = Math.min(failCount, MAX_FAILURE_PER_TICK);
            for (int i = 0; i < failCount; i++) {
                int packed = mapped.getInt((i + 1) * 4);
                int flatIndex = packed >>> 4;
                byte failType = (byte) (packed & 0xF);

                // H4-fix: 邊界檢查
                if (flatIndex < 0 || flatIndex >= buf.getN()) continue;

                BlockPos pos = buf.fromFlatIndex(flatIndex);
                FailureType type = switch (failType) {
                    case FAIL_CANTILEVER -> FailureType.CANTILEVER_BREAK;
                    case FAIL_CRUSHING -> FailureType.CRUSHING;
                    case FAIL_NO_SUPPORT -> FailureType.NO_SUPPORT;
                    case FAIL_TENSION -> FailureType.TENSION_BREAK;
                    default -> null;
                };
                if (type != null) {
                    CollapseManager.triggerPFSFCollapse(level, pos, type);
                }
            }
            buf.markDirty();
            PFSFScheduler.onCollapseTriggered(buf);
        }

        VulkanComputeContext.unmapBuffer(frame.readbackStagingBuf[1]);

        // ─── GPU-side phi max 歸約結果 → 精確發散偵測 ───
        if (frame.phiMaxStagingBuf != null) {
            java.nio.ByteBuffer phiMaxMapped = VulkanComputeContext.mapBuffer(
                    frame.phiMaxStagingBuf[1], Float.BYTES);
            float maxPhiNow = phiMaxMapped.getFloat(0);
            VulkanComputeContext.unmapBuffer(frame.phiMaxStagingBuf[1]);

            PFSFScheduler.checkDivergence(buf, maxPhiNow);

            // 釋放 phi max 中間 buffer（deferred free）
            if (frame.phiMaxPartialBuf != null) {
                for (int i = 0; i < frame.phiMaxPartialBuf.length; i += 2) {
                    VulkanComputeContext.freeBuffer(frame.phiMaxPartialBuf[i], frame.phiMaxPartialBuf[i + 1]);
                }
                frame.phiMaxPartialBuf = null;
            }
        }

        // ─── M10: 週期性應力同步到客戶端 ───
        syncStressToClients(buf, level);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Stress Sync (M10)
    // ═══════════════════════════════════════════════════════════════

    private static void syncStressToClients(PFSFIslandBuffer buf, ServerLevel level) {
        int counter = syncCounters.merge(buf.getIslandId(), 1, Integer::sum);
        if (counter % STRESS_SYNC_INTERVAL != 0) return;

        var stressField = PFSFStressExtractor.extractStressField(buf);
        Map<BlockPos, Float> stressMap = stressField != null ? stressField.stressValues() : null;
        if (stressMap == null || stressMap.isEmpty()) return;

        // 過濾低應力（Object2FloatOpenHashMap 減少 Float boxing 開銷）
        Object2FloatOpenHashMap<BlockPos> filtered = new Object2FloatOpenHashMap<>(stressMap.size());
        for (Map.Entry<BlockPos, Float> entry : stressMap.entrySet()) {
            if (entry.getValue() >= 0.3f) {
                filtered.put(entry.getKey(), entry.getValue().floatValue());
            }
        }
        if (filtered.isEmpty()) return;

        BlockPos center = buf.getOrigin().offset(buf.getLx() / 2, buf.getLy() / 2, buf.getLz() / 2);
        com.blockreality.api.network.PFSFStressSyncPacket packet =
                new com.blockreality.api.network.PFSFStressSyncPacket(buf.getIslandId(), filtered);
        com.blockreality.api.network.BRNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.NEAR.with(
                        () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                center.getX(), center.getY(), center.getZ(),
                                64.0, level.dimension())),
                packet);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public API: Sparse Dirty Notification
    // ═══════════════════════════════════════════════════════════════

    /**
     * 方塊放置/破壞時由事件處理器呼叫。
     * 只標記變更的體素和其鄰居為 dirty（而非整個 island）。
     */
    public static void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial,
                                          Set<BlockPos> anchors) {
        PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                islandId, PFSFSparseUpdate::new);
        PFSFIslandBuffer buf = PFSFBufferManager.buffers.get(islandId);

        if (buf == null || !buf.contains(pos)) {
            sparse.markFullRebuild();
            return;
        }

        int flatIdx = buf.flatIndex(pos);
        float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;

        float source = newMaterial != null
                ? (float) (newMaterial.getDensity() * fillRatio * PFSFConstants.GRAVITY * PFSFConstants.BLOCK_VOLUME)
                : 0.0f;
        byte type = newMaterial == null ? VOXEL_AIR
                : (anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID);
        float maxPhi = newMaterial != null ? PFSFSourceBuilder.computeMaxPhi(newMaterial) : 0.0f;
        float rcomp = newMaterial != null ? (float) newMaterial.getRcomp() : 0.0f;
        float[] cond = new float[6];

        sparse.markVoxelDirty(new PFSFSparseUpdate.VoxelUpdate(
                flatIdx, source, type, maxPhi, rcomp, cond));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════════════════════════

    public static void setMaterialLookup(Function<BlockPos, RMaterial> lookup) { materialLookup = lookup; }
    public static void setAnchorLookup(Function<BlockPos, Boolean> lookup) { anchorLookup = lookup; }
    public static void setFillRatioLookup(Function<BlockPos, Float> lookup) { fillRatioLookup = lookup; }
    /** v2.1: 設定 ICuringManager 水化度查詢（null → 全部視為完全養護）。 */
    public static void setCuringLookup(Function<BlockPos, Float> lookup) { curingLookup = lookup; }
    /** v2.1: 設定全域風向向量（null → 無風壓）。建議每秒更新 2 次。 */
    public static void setWindVector(net.minecraft.world.phys.Vec3 wind) { currentWindVec = wind; }

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: Phase-Field Evolve Dispatch
    // ═══════════════════════════════════════════════════════════════

    /**
     * 錄製 Phase-Field Evolution GPU dispatch。
     * 使用 Ambati 2015 混合相場公式，每 SCAN_INTERVAL 步執行一次。
     * pipeline：phaseFieldPipeline（7 個 binding，24 bytes push constant）
     */
    private static void recordPhaseFieldEvolve(org.lwjgl.vulkan.VkCommandBuffer cmdBuf,
                                                 PFSFIslandBuffer buf, long pool) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(pool, PFSFPipelineFactory.phaseFieldDSLayout);
            // binding 0: phi（唯讀，勢能場）
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(),          0, buf.getPhiSize());
            // binding 1: hField（讀寫，歷史應變能）
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getHFieldBuf(),       0, buf.getHFieldSize());
            // binding 2: dField（讀寫，損傷場）
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDFieldBuf(),       0, buf.getDFieldSize());
            // binding 3: conductivity（唯讀，SoA）
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            // binding 4: type
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(),         0, buf.getTypeSize());
            // binding 5: failFlags（寫入，d>0.95 觸發）
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(),    0, buf.getTypeSize());
            // binding 6: hydration（唯讀，ICuringManager 水化度）
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getHydrationBuf(),    0, buf.getHydrationSize());

            org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipelineLayout, 0, stack.longs(ds), null);

            // push constants: Lx, Ly, Lz (3×uint=12) + l0, gcBase, relax (3×float=12) = 24 bytes
            // 暫用混凝土 G_c 預設值；未來可依 island 主材料動態選擇
            java.nio.ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(PHASE_FIELD_L0)
              .putFloat(G_C_CONCRETE)
              .putFloat(PHASE_FIELD_RELAX);
            pc.flip();

            org.lwjgl.vulkan.VK10.vkCmdPushConstants(
                    cmdBuf, PFSFPipelineFactory.phaseFieldPipelineLayout,
                    org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            org.lwjgl.vulkan.VK10.vkCmdDispatch(cmdBuf, PFSFVCycleRecorder.ceilDiv(buf.getN(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** 供 PFSFFailureRecorder 存取 descriptor pool。 */
    static long getDescriptorPool() { return descriptorPoolMgr != null ? descriptorPoolMgr.getPool() : 0; }

    public static boolean isAvailable() { return available; }

    public static void shutdown() {
        if (!initialized) return;

        PFSFAsyncCompute.shutdown();
        PFSFBufferManager.freeAll();

        // C8-fix: 銷毀 descriptor pool
        if (descriptorPoolMgr != null) {
            descriptorPoolMgr.destroy();
            descriptorPoolMgr = null;
        }

        initialized = false;
        available = false;
        LOGGER.info("[PFSF] Engine shut down");
    }

    public static String getStats() {
        if (!available) return "PFSF Engine: DISABLED";
        return String.format("PFSF Engine: %d islands buffered, %d total voxels",
                PFSFBufferManager.buffers.size(),
                PFSFBufferManager.buffers.values().stream().mapToInt(PFSFIslandBuffer::getN).sum());
    }

    /**
     * 從 GPU 讀回 stress field（供外部呼叫）。
     */
    static StressField extractStressField(PFSFIslandBuffer buf) {
        return PFSFStressExtractor.extractStressField(buf);
    }

    /** 公開 buffer 移除（供 StructureIslandRegistry 回調）。 */
    public static void removeBuffer(int islandId) {
        PFSFBufferManager.removeBuffer(islandId);
    }
}
