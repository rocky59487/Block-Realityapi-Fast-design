package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.FailureType;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import com.blockreality.api.physics.StressField;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 引擎實例 — {@link IPFSFRuntime} 的 Java/LWJGL Vulkan 實作。
 *
 * <p>從原 {@link PFSFEngine} 的 static 邏輯提取為實例類別，
 * 以支援 Strategy pattern 後端切換（Phase 0, libpfsf 遷移計畫）。</p>
 *
 * <p>{@link PFSFEngine} 保留 static facade 向下相容所有呼叫者。</p>
 *
 * @since v0.3a (libpfsf Phase 0)
 * @see IPFSFRuntime
 * @see PFSFEngine
 */
public final class PFSFEngineInstance implements IPFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    // ─── State ───
    private boolean initialized = false;
    private boolean available = false;

    // ─── Descriptor Pool ───
    private DescriptorPoolManager descriptorPoolMgr;

    // ─── VRAM 感知組件 ───
    private final IslandBufferEvictor evictor = new IslandBufferEvictor();
    private long tickCounter = 0;

    // ─── Material lookup ───
    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;
    private Function<BlockPos, Float> fillRatioLookup;
    private Function<BlockPos, Float> curingLookup = null;
    private Vec3 currentWindVec = null;

    /** M10: 同步 tick 計數器（每 island） */
    private final ConcurrentHashMap<Integer, Integer> syncCounters = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void init() {
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

    @Override
    public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;
        tickCounter++;

        // ─── Phase 1: 回收完成的 GPU 結果（非阻塞） ───
        PFSFAsyncCompute.pollCompleted();

        // 按需重置 descriptor pool
        descriptorPoolMgr.tickResetIfNeeded();
        long descriptorPool = descriptorPoolMgr.getPool();

        // 按需驅逐閒置 island
        VramBudgetManager budgetMgr = VulkanComputeContext.getVramBudgetManager();
        evictor.evictIfNeeded(tickCounter, budgetMgr, PFSFBufferManager.buffers);

        long startTime = System.nanoTime();

        // ─── Batch 收集（Ping-Pong 多 island 並行）───
        List<PFSFAsyncCompute.ComputeFrame> batch = new ArrayList<>();
        List<Consumer<Void>> callbacks = new ArrayList<>();

        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(currentEpoch).entrySet()) {

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= BRConfig.getPFSFTickBudgetMs()) break;

            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;

            evictor.touchIsland(islandId, tickCounter);

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            if (buf == null) continue;

            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            // ─── Phase 2: 取得非同步 frame ───
            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) break;
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

            if (batch.size() >= 3) break;
        }

        // ─── Batch Submit ───
        if (!batch.isEmpty()) {
            PFSFAsyncCompute.submitBatch(batch, callbacks);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU Result Processing
    // ═══════════════════════════════════════════════════════════════

    private void processCompletedFrame(PFSFAsyncCompute.ComputeFrame frame,
                                       PFSFIslandBuffer buf, ServerLevel level) {
        if (frame.readbackStagingBuf == null) return;

        ByteBuffer mapped = VulkanComputeContext.mapBuffer(
                frame.readbackStagingBuf[1], frame.readbackStagingSize);
        int failCount = mapped.getInt(0);

        if (failCount > 0) {
            failCount = Math.min(failCount, MAX_FAILURE_PER_TICK);
            for (int i = 0; i < failCount; i++) {
                int packed = mapped.getInt((i + 1) * 4);
                int flatIndex = packed >>> 4;
                byte failType = (byte) (packed & 0xF);

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
            ByteBuffer phiMaxMapped = VulkanComputeContext.mapBuffer(
                    frame.phiMaxStagingBuf[1], Float.BYTES);
            float maxPhiNow = phiMaxMapped.getFloat(0);
            VulkanComputeContext.unmapBuffer(frame.phiMaxStagingBuf[1]);

            PFSFScheduler.checkDivergence(buf, maxPhiNow);

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

    private void syncStressToClients(PFSFIslandBuffer buf, ServerLevel level) {
        int counter = syncCounters.merge(buf.getIslandId(), 1, Integer::sum);
        if (counter % STRESS_SYNC_INTERVAL != 0) return;

        var stressField = PFSFStressExtractor.extractStressField(buf);
        Map<BlockPos, Float> stressMap = stressField != null ? stressField.stressValues() : null;
        if (stressMap == null || stressMap.isEmpty()) return;

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
    //  Sparse Dirty Notification
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial,
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

    @Override
    public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) { this.materialLookup = lookup; }
    @Override
    public void setAnchorLookup(Function<BlockPos, Boolean> lookup) { this.anchorLookup = lookup; }
    @Override
    public void setFillRatioLookup(Function<BlockPos, Float> lookup) { this.fillRatioLookup = lookup; }
    @Override
    public void setCuringLookup(Function<BlockPos, Float> lookup) { this.curingLookup = lookup; }
    @Override
    public void setWindVector(Vec3 wind) { this.currentWindVec = wind; }

    // ═══════════════════════════════════════════════════════════════
    //  Phase-Field Evolve Dispatch
    // ═══════════════════════════════════════════════════════════════

    private void recordPhaseFieldEvolve(org.lwjgl.vulkan.VkCommandBuffer cmdBuf,
                                        PFSFIslandBuffer buf, long pool) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(pool, PFSFPipelineFactory.phaseFieldDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(),          0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getHFieldBuf(),       0, buf.getHFieldSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDFieldBuf(),       0, buf.getDFieldSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(),         0, buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(),    0, buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getHydrationBuf(),    0, buf.getHydrationSize());

            org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipelineLayout, 0, stack.longs(ds), null);

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

    /** 供 PFSFFailureRecorder 等內部組件存取 descriptor pool。 */
    long getDescriptorPool() { return descriptorPoolMgr != null ? descriptorPoolMgr.getPool() : 0; }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public void shutdown() {
        if (!initialized) return;

        PFSFAsyncCompute.shutdown();
        PFSFBufferManager.freeAll();

        if (descriptorPoolMgr != null) {
            descriptorPoolMgr.destroy();
            descriptorPoolMgr = null;
        }

        initialized = false;
        available = false;
        LOGGER.info("[PFSF] Engine shut down");
    }

    @Override
    public String getStats() {
        if (!available) return "PFSF Engine: DISABLED";
        return String.format("PFSF Engine: %d islands buffered, %d total voxels",
                PFSFBufferManager.buffers.size(),
                PFSFBufferManager.buffers.values().stream().mapToInt(PFSFIslandBuffer::getN).sum());
    }

    StressField extractStressField(PFSFIslandBuffer buf) {
        return PFSFStressExtractor.extractStressField(buf);
    }

    @Override
    public void removeBuffer(int islandId) {
        PFSFBufferManager.removeBuffer(islandId);
    }
}
