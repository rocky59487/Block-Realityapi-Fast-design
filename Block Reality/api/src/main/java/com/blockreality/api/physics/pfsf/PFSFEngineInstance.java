package com.blockreality.api.physics.pfsf;

import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.fragment.TippingCollapseContext;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.AnchorContinuityChecker;
import com.blockreality.api.physics.FailureType;
import com.blockreality.api.physics.OverturningStabilityChecker;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import com.blockreality.api.physics.StressField;
import com.blockreality.api.physics.fluid.FluidStructureCoupler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 引擎實例 — v0.2a + BIFROST 混合路由。{@link PFSFEngine} 保留 static facade，
 * 委託給 singleton instance；實作 {@link IPFSFRuntime} 以支援 hybrid dispatch。
 */
public final class PFSFEngineInstance implements IPFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    private boolean initialized = false;
    private boolean available = false;
    private DescriptorPoolManager descriptorPoolMgr;
    private final IslandBufferEvictor evictor = new IslandBufferEvictor();
    private int tickCounter = 0;
    private long lastProcessedEpoch = -1;

    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;
    private Function<BlockPos, Float> fillRatioLookup;
    private Function<BlockPos, Float> curingLookup;
    private Vec3 currentWindVec;

    private final PFSFResultProcessor resultProcessor = new PFSFResultProcessor();
    private final PFSFDispatcher dispatcher = new PFSFDispatcher();
    private final List<PFSFAsyncCompute.ComputeFrame> batch = new ArrayList<>(3);
    private final List<Runnable> callbacks = new ArrayList<>(3);

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
            long pool = VulkanComputeContext.createIsolatedDescriptorPool(2048, 8192, "PFSF");
            if (pool == 0) {
                LOGGER.error("[PFSF] createIsolatedDescriptorPool returned 0 handle — init aborted");
                available = false;
                return;
            }
            descriptorPoolMgr = new DescriptorPoolManager(pool, 2048, "PFSF");
            available = true;
            LOGGER.info("[PFSF] Engine initialized successfully");
        } catch (Throwable e) {
            LOGGER.error("[PFSF] Engine init failed", e);
            available = false;
        }
    }

    @Override
    public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;
        PFSFAsyncCompute.pollCompleted();
        descriptorPoolMgr.tickResetIfNeeded();
        evictor.tick();
        tickCounter++;
        if (tickCounter % evictor.getCheckInterval() == 0) {
            evictor.evictIfNeeded(VulkanComputeContext.getVramBudgetManager());
        }
        final long descriptorPool = descriptorPoolMgr.getPool();
        final VramBudgetManager vramMgr = VulkanComputeContext.getVramBudgetManager();
        final long startTime = System.nanoTime();
        batch.clear();
        callbacks.clear();

        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(lastProcessedEpoch).entrySet()) {
            if ((System.nanoTime() - startTime) / 1_000_000 >= BRConfig.getPFSFTickBudgetMs()) break;
            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;
            // Overturning first so a tipping island bypasses GPU budget; ML routing second
            // so FNO can skip PFSF for regular geometry.
            if (tryOverturningHandoff(level, island, islandId)) continue;
            if (tryMLInferenceHandoff(level, island, islandId, currentEpoch)) continue;

            evictor.touchIsland(islandId);
            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            if (buf == null) continue;
            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) { flushBatch(); break; }
            frame.islandId = islandId;

            /* v0.4 M2: fire SPI aug binders before uploading source /
             * conductivity so the registry carries this tick's fresh
             * per-voxel contributions. runBinders swallows binder
             * exceptions, so a broken SPI never breaks the tick. */
            PFSFAugmentationHost.runBinders(islandId);

            uploadIslandData(frame, buf, island, level, islandId, descriptorPool);
            if (updateLodAndSkipDormant(buf, players, islandId)) continue;
            float change = computeConvergenceChange(buf);
            if (skipConvergedIsland(buf, change, islandId)) continue;
            int steps = computeAdjustedSteps(buf, vramMgr, change);
            if (!recordGpuDispatch(frame, buf, steps, islandId, descriptorPool)) continue;

            scheduleFrameCompletion(frame, buf, level);
            StructureIslandRegistry.markProcessed(islandId);
            if (batch.size() >= 3) flushBatch();
        }
        flushBatch();
        lastProcessedEpoch = currentEpoch;
    }

    private Set<BlockPos> gatherStructuralAnchors(ServerLevel level, StructureIsland island) {
        AnchorContinuityChecker checker = AnchorContinuityChecker.getInstance();
        Set<BlockPos> anchors = new HashSet<>();
        for (BlockPos p : island.getMembers()) if (checker.isAnchored(level, p)) anchors.add(p);
        return anchors;
    }

    private boolean tryOverturningHandoff(ServerLevel level, StructureIsland island, int islandId) {
        if (!BRConfig.isOverturningEnabled() || island.getBlockCount() < 4) return false;
        Set<BlockPos> anchors = gatherStructuralAnchors(level, island);
        if (anchors.isEmpty()) return false;
        OverturningStabilityChecker.Result stability = OverturningStabilityChecker.check(
                island.getCoM(materialLookup), anchors, BRConfig.getStabilityDeadband());
        if (stability.state() != OverturningStabilityChecker.State.TIPPING) return false;
        TippingCollapseContext.set(stability);
        CollapseManager.enqueueCollapse(level, island.getMembers(), FailureType.OVERTURNING);
        StructureIslandRegistry.markProcessed(islandId);
        return true;
    }

    private boolean tryMLInferenceHandoff(ServerLevel level, StructureIsland island,
                                           int islandId, long currentEpoch) {
        HybridPhysicsRouter router = PFSFEngine.getRouter();
        if (!router.isFnoAvailable()) return false;
        // A2: AnchorContinuityChecker catches suspended structures (bridges, arches)
        // whose anchors aren't at the bottom row.
        Set<BlockPos> anchors = gatherStructuralAnchors(level, island);
        if (anchors.isEmpty()) {
            BlockPos minC = island.getMinCorner();
            for (BlockPos p : island.getMembers()) {
                if (p.getY() == minC.getY()) { anchors.add(p); break; }
            }
        }
        if (router.route(islandId, island.getMembers(), anchors, currentEpoch)
                != HybridPhysicsRouter.Backend.FNO) return false;
        OnnxPFSFRuntime onnx = router.getOnnxRuntime();
        if (onnx == null) return false;
        onnx.setMaterialLookup(materialLookup);
        onnx.setAnchorLookup(anchorLookup);
        OnnxPFSFRuntime.InferenceResult mlResult = onnx.infer(island);
        if (mlResult == null) return false;
        applyMLResult(mlResult, island, level);
        StructureIslandRegistry.markProcessed(islandId);
        return true;
    }

    private void uploadIslandData(PFSFAsyncCompute.ComputeFrame frame, PFSFIslandBuffer buf,
                                   StructureIsland island, ServerLevel level,
                                   int islandId, long descriptorPool) {
        PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                islandId, PFSFSparseUpdate::new);
        PFSFEngine.UploadContext ctx = new PFSFEngine.UploadContext(island, level,
                materialLookup, anchorLookup, fillRatioLookup, curingLookup, currentWindVec,
                FluidStructureCoupler.getPressureLookup());
        dispatcher.handleDataUpload(frame, buf, sparse, ctx, descriptorPool);
    }

    private boolean updateLodAndSkipDormant(PFSFIslandBuffer buf, List<ServerPlayer> players, int islandId) {
        int lod = PFSFLODPolicy.computeLodLevel(buf, players);
        buf.setLodLevel(lod);
        buf.decrementWakeTicks();
        if (lod == LOD_DORMANT && buf.getWakeTicksRemaining() <= 0) {
            StructureIslandRegistry.markProcessed(islandId);
            return true;
        }
        return false;
    }

    private static float computeConvergenceChange(PFSFIslandBuffer buf) {
        if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0)
            return Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
        return 0;
    }

    private static boolean skipConvergedIsland(PFSFIslandBuffer buf, float change, int islandId) {
        if (change > 0 && change < CONVERGENCE_SKIP_THRESHOLD) {
            if (buf.getStableTickCount() < Integer.MAX_VALUE) buf.incrementStableCount();
        } else if (change >= CONVERGENCE_SKIP_THRESHOLD) {
            buf.resetStableCount();
        }
        if (buf.getStableTickCount() > STABLE_TICK_SKIP_COUNT) {
            StructureIslandRegistry.markProcessed(islandId);
            return true;
        }
        return false;
    }

    private static int computeAdjustedSteps(PFSFIslandBuffer buf, VramBudgetManager vramMgr, float change) {
        int steps = PFSFLODPolicy.adjustStepsForLod(
                ComputeRangePolicy.adjustSteps(PFSFScheduler.recommendSteps(buf, false, false), vramMgr),
                buf.getLodLevel());
        if (change > 0 && change < CONVERGENCE_REDUCE_THRESHOLD) steps = Math.max(1, steps / 2);
        if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
            if (change < EARLY_TERM_TIGHT)      steps = Math.max(1, steps / 4);
            else if (change < EARLY_TERM_LOOSE) steps = Math.max(2, steps / 2);
        }
        if (buf.cachedMacroResiduals != null) {
            float activeRatio = PFSFScheduler.getActiveRatio(buf.cachedMacroResiduals);
            if (activeRatio < 0.1f)      steps = Math.max(1, steps / 4);
            else if (activeRatio < 0.5f) steps = Math.max(1, steps / 2);
        }
        return steps;
    }

    private boolean recordGpuDispatch(PFSFAsyncCompute.ComputeFrame frame, PFSFIslandBuffer buf,
                                       int steps, int islandId, long descriptorPool) {
        try {
            dispatcher.recordSolveSteps(frame.cmdBuf, buf, steps, descriptorPool);
            if (steps > 0) {
                // Barriers protect solver → phase-field → failure_scan from GPU WAW hazards.
                VulkanComputeContext.computeBarrier(frame.cmdBuf);
                if (buf.getStableTickCount() <= STABLE_TICK_PHASE_FIELD_SKIP) {
                    dispatcher.recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
                }
                VulkanComputeContext.computeBarrier(frame.cmdBuf);
                dispatcher.recordFailureDetection(frame, buf, descriptorPool);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[PFSF] GPU recording failed for island {}: {}", islandId, e.getMessage());
            PFSFAsyncCompute.releaseFrame(frame);
            return false;
        }
    }

    private void scheduleFrameCompletion(PFSFAsyncCompute.ComputeFrame frame,
                                          PFSFIslandBuffer buf, ServerLevel level) {
        buf.acquire();  // paired with release() inside the callback
        batch.add(frame);
        callbacks.add(() -> {
            try { resultProcessor.processCompletedFrame(frame, buf, level); }
            finally { buf.release(); }
        });
    }

    private void flushBatch() {
        if (batch.isEmpty()) return;
        PFSFAsyncCompute.submitBatch(batch, callbacks);
        batch.clear();
        callbacks.clear();
    }

    @Override
    public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial, Set<BlockPos> anchors) {
        PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                islandId, PFSFSparseUpdate::new);
        PFSFIslandBuffer buf = PFSFBufferManager.buffers.get(islandId);
        if (buf == null || !buf.contains(pos)) { sparse.markFullRebuild(); return; }
        // 方塊/負載變化 → 重置收斂計數、喚醒 DORMANT、更新拓撲版本。
        buf.resetStableCount();
        if (buf.getLodLevel() == LOD_DORMANT) buf.setWakeTicksRemaining(LOD_WAKE_TICKS);
        int flatIdx = buf.flatIndex(pos);
        boolean wasAir = (sparse.getLastKnownType(flatIdx) == VOXEL_AIR);
        if (newMaterial == null || wasAir) buf.incrementTopologyVersion();
        // Sparse conductivity rebuild incomplete (new float[6] isolates the voxel);
        // force full rebuild until Phase 6 lands proper delta upload.
        sparse.markFullRebuild();
    }

    @Override public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) { this.materialLookup = lookup; }
    @Override public void setAnchorLookup(Function<BlockPos, Boolean> lookup)    { this.anchorLookup = lookup; }
    @Override public void setFillRatioLookup(Function<BlockPos, Float> lookup)   { this.fillRatioLookup = lookup; }
    @Override public void setCuringLookup(Function<BlockPos, Float> lookup)      { this.curingLookup = lookup; }

    /* v0.4 M2e — read-side accessors so aug binders can source voxel
     * data via the same hooks the engine already uses. These are NOT on
     * IPFSFRuntime because OnnxPFSFRuntime has no Java-side lookup state —
     * the getters are specific to the native / hybrid backend. */
    public Function<BlockPos, RMaterial> getMaterialLookup() { return materialLookup; }
    public Function<BlockPos, Boolean>   getAnchorLookup()   { return anchorLookup; }
    public Function<BlockPos, Float>     getFillRatioLookup() { return fillRatioLookup; }
    public Function<BlockPos, Float>     getCuringLookup()   { return curingLookup; }
    public net.minecraft.world.phys.Vec3 getCurrentWindVec() { return currentWindVec; }
    @Override public void setWindVector(Vec3 wind)                                { this.currentWindVec = wind; }

    long getDescriptorPool() { return descriptorPoolMgr != null ? descriptorPoolMgr.getPool() : 0; }
    @Override public boolean isAvailable() { return available; }

    @Override
    public void shutdown() {
        if (!initialized) return;
        PFSFAsyncCompute.shutdown();
        PFSFBufferManager.freeAll();
        if (descriptorPoolMgr != null) { descriptorPoolMgr.destroy(); descriptorPoolMgr = null; }
        evictor.reset();
        initialized = false;
        available = false;
        LOGGER.info("[PFSF] Engine shut down");
    }

    @Override
    public String getStats() {
        if (!available) return "PFSF Engine: DISABLED";
        VramBudgetManager vramMgr = VulkanComputeContext.getVramBudgetManager();
        return String.format("PFSF Engine: %d islands buffered, %d total voxels, VRAM: %.1f%% (desc pool: %.0f%%)",
                PFSFBufferManager.buffers.size(),
                PFSFBufferManager.buffers.values().stream().mapToInt(PFSFIslandBuffer::getN).sum(),
                vramMgr.getPressure() * 100,
                descriptorPoolMgr != null ? descriptorPoolMgr.getUsageRatio() * 100 : 0f);
    }

    StressField extractStressField(PFSFIslandBuffer buf) { return PFSFStressExtractor.extractStressField(buf); }

    @Override public void removeBuffer(int islandId) { PFSFBufferManager.removeBuffer(islandId); }

    /** BIFROST: apply ML inference result to game world. */
    private void applyMLResult(OnnxPFSFRuntime.InferenceResult result, StructureIsland island, ServerLevel level) {
        for (BlockPos pos : island.getMembers()) {
            // Per-block Rcomp from materialLookup; 30 MPa (concrete) fallback.
            float rcompMPa = 30.0f;
            if (materialLookup != null) {
                RMaterial mat = materialLookup.apply(pos);
                if (mat != null) rcompMPa = (float) mat.getRcomp();
            }
            float ratio = result.getStressRatio(pos, rcompMPa);
            if (ratio > 1.0f) {
                FailureType type = ratio > 2.0f ? FailureType.CRUSHING : FailureType.TENSION_BREAK;
                CollapseManager.triggerPFSFCollapse(level, pos, type);
            }
        }
    }
}
