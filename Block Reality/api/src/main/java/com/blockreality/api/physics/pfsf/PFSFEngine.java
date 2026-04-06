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

    // ─── Descriptor Pool ───
    private static long descriptorPool;

    // ─── Material lookup (set by Mod initialization) ───
    private static Function<BlockPos, RMaterial> materialLookup;
    private static Function<BlockPos, Boolean> anchorLookup;
    private static Function<BlockPos, Float> fillRatioLookup;

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
            // A2-fix: 增大 pool 並每 tick reset
            descriptorPool = VulkanComputeContext.createDescriptorPool(2048, 8192);
            available = true;
            LOGGER.info("[PFSF] Engine initialized successfully");
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

        // ─── Phase 1: 回收完成的 GPU 結果（非阻塞） ───
        PFSFAsyncCompute.pollCompleted();

        // A2-fix: 每 tick 重置 descriptor pool
        VulkanComputeContext.resetDescriptorPool(descriptorPool);

        long startTime = System.nanoTime();

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

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            // ─── Phase 2: 取得非同步 frame ───
            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) continue;
            frame.islandId = islandId;

            // Phase 3: 稀疏更新或全量重建
            if (sparse.hasPendingUpdates()) {
                List<PFSFSparseUpdate.VoxelUpdate> updates = sparse.drainUpdates();
                if (updates == null) {
                    // 全量重建
                    PFSFDataBuilder.updateSourceAndConductivity(buf, island, level,
                            materialLookup, anchorLookup, fillRatioLookup);
                    buf.markClean();
                } else if (!updates.isEmpty()) {
                    // 稀疏更新
                    int count = sparse.packUpdates(updates);
                    PFSFFailureRecorder.recordSparseScatter(frame.cmdBuf, buf, sparse, count, descriptorPool);
                    buf.markClean();
                }
            }

            // ─── Phase 4: Jacobi 迭代 + W-Cycle ───
            // v2: 自適應迭代 — 收斂 island 跳過（CPU 近似，省 90% ALU）
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                float change = Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
                if (change < PFSFScheduler.MACRO_BLOCK_CONVERGENCE_THRESHOLD) {
                    StructureIslandRegistry.markProcessed(islandId);
                    continue; // island 已收斂
                }
            }

            boolean hasCollapse = false;
            int steps = PFSFScheduler.recommendSteps(buf, false, hasCollapse);

            // H1-fix: V-Cycle 內部已含 swap，外部只對單步 Jacobi swap
            for (int k = 0; k < steps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    PFSFVCycleRecorder.recordVCycle(frame.cmdBuf, buf, descriptorPool);
                } else {
                    float omega = PFSFScheduler.getTickOmega(buf);
                    PFSFVCycleRecorder.recordJacobiStep(frame.cmdBuf, buf, omega, descriptorPool);
                    buf.swapPhi();
                }
            }

            // ─── Phase 5: failure scan + compact readback ───
            if (steps > 0) {
                PFSFFailureRecorder.recordFailureScan(frame.cmdBuf, buf, descriptorPool);
                PFSFFailureRecorder.recordFailureCompact(frame.cmdBuf, buf, frame, descriptorPool);
                PFSFFailureRecorder.recordPhiMaxReduction(frame.cmdBuf, buf, frame);
            }

            // ─── Phase 6: 非阻塞提交 ───
            // A4-fix: 引用計數保護
            buf.retain();
            final PFSFIslandBuffer finalBuf = buf;
            PFSFAsyncCompute.submitAsync(frame, v -> {
                try {
                    processCompletedFrame(frame, finalBuf, level);
                } finally {
                    finalBuf.release();
                }
            });

            StructureIslandRegistry.markProcessed(islandId);
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

        // 過濾低應力
        Map<BlockPos, Float> filtered = new HashMap<>();
        for (Map.Entry<BlockPos, Float> entry : stressMap.entrySet()) {
            if (entry.getValue() >= 0.3f) {
                filtered.put(entry.getKey(), entry.getValue());
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
                ? PFSFSourceBuilder.computeSource(newMaterial, fillRatio, 0, 0.0)
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

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** 供 PFSFFailureRecorder 存取 descriptor pool。 */
    static long getDescriptorPool() { return descriptorPool; }

    public static boolean isAvailable() { return available; }

    public static void shutdown() {
        if (!initialized) return;

        PFSFAsyncCompute.shutdown();
        PFSFBufferManager.freeAll();

        // C8-fix: 銷毀 descriptor pool
        if (descriptorPool != 0) {
            VulkanComputeContext.destroyDescriptorPool(descriptorPool);
            descriptorPool = 0;
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
