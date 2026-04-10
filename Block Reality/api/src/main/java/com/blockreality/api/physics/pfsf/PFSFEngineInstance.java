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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 引擎實例 — v0.2a + BIFROST：所有狀態從 static 移到實例欄位。
 *
 * <p>{@link PFSFEngine} 保留 static facade，委託給 singleton instance。
 * 實作 {@link IPFSFRuntime} 介面以支援 BIFROST 混合路由。</p>
 *
 * @see IPFSFRuntime
 * @see HybridPhysicsRouter
 */
public final class PFSFEngineInstance implements IPFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    // ─── State ───
    private boolean initialized = false;
    private boolean available = false;

    // ─── Descriptor Pool（v0.2a: on-demand reset） ───
    private DescriptorPoolManager descriptorPoolMgr;

    // ─── v0.2a: VRAM-aware island management ───
    private final IslandBufferEvictor evictor = new IslandBufferEvictor();
    private int tickCounter = 0;

    // ─── Material lookup ───
    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;
    private Function<BlockPos, Float> fillRatioLookup;
    private Function<BlockPos, Float> curingLookup;
    private Vec3 currentWindVec;

    // ─── v0.2a 委託組件 ───
    private final PFSFResultProcessor resultProcessor = new PFSFResultProcessor();
    private final PFSFDispatcher dispatcher = new PFSFDispatcher();

    // ─── v0.2a Ping-Pong buffers (reused to reduce GC pressure) ───
    private final List<PFSFAsyncCompute.ComputeFrame> batch = new ArrayList<>(3);
    private final List<Runnable> callbacks = new ArrayList<>(3);

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
            long pool = VulkanComputeContext.createIsolatedDescriptorPool(2048, 8192, "PFSF");
            descriptorPoolMgr = new DescriptorPoolManager(pool, 2048, "PFSF");
            available = true;
            LOGGER.info("[PFSF] Engine initialized successfully");
        } catch (Throwable e) {
            LOGGER.error("[PFSF] Engine init failed", e);
            available = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main Tick Loop
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;

        PFSFAsyncCompute.pollCompleted();

        // v0.2a: on-demand descriptor pool reset
        descriptorPoolMgr.tickResetIfNeeded();

        // v0.2a: LRU evictor tick + periodic check
        evictor.tick();
        tickCounter++;
        if (tickCounter % evictor.getCheckInterval() == 0) {
            evictor.evictIfNeeded(VulkanComputeContext.getVramBudgetManager());
        }

        long descriptorPool = descriptorPoolMgr.getPool();
        VramBudgetManager vramMgr = VulkanComputeContext.getVramBudgetManager();
        long startTime = System.nanoTime();

        // v0.2a: Ping-Pong parallel — collect frames into batch (max 3)
        batch.clear();
        callbacks.clear();

        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(currentEpoch).entrySet()) {

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= BRConfig.getPFSFTickBudgetMs()) break;

            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;

            // ═══ B5: Gravity overturning / seesaw stability check ═══
            // Must run BEFORE BIFROST so that an overturning island is consumed
            // immediately without spending GPU budget on a structure about to tip.
            if (BRConfig.isOverturningEnabled() && island.getBlockCount() >= 4) {
                AnchorContinuityChecker overAnchorChecker = AnchorContinuityChecker.getInstance();
                Set<BlockPos> overAnchors = new HashSet<>();
                for (BlockPos p : island.getMembers()) {
                    if (overAnchorChecker.isAnchored(level, p)) overAnchors.add(p);
                }
                if (!overAnchors.isEmpty()) {
                    double[] com = island.getCoM(materialLookup);
                    OverturningStabilityChecker.Result stability =
                        OverturningStabilityChecker.check(
                            com, overAnchors, BRConfig.getStabilityDeadband());

                    if (stability.state() == OverturningStabilityChecker.State.TIPPING) {
                        // Store tipping result so StructureFragmentDetector can use physics-correct ω
                        TippingCollapseContext.set(stability);
                        CollapseManager.enqueueCollapse(
                            level, island.getMembers(), FailureType.OVERTURNING);
                        StructureIslandRegistry.markProcessed(islandId);
                        continue; // overturning handled — skip GPU solve for this island
                    }
                }
            }

            // ═══ BIFROST: ML routing — FNO for irregular, PFSF for regular ═══
            HybridPhysicsRouter router = PFSFEngine.getRouter();
            if (router.isFnoAvailable()) {
                // A2 fix: use AnchorContinuityChecker for structurally-correct anchor detection.
                // The old heuristic (p.getY() == minCorner.getY()) was wrong for suspended
                // structures (bridges, arches) whose anchors are not at the bottom row.
                AnchorContinuityChecker anchorChecker = AnchorContinuityChecker.getInstance();
                Set<BlockPos> fallbackAnchors = new HashSet<>();
                for (BlockPos p : island.getMembers()) {
                    if (anchorChecker.isAnchored(level, p)) fallbackAnchors.add(p);
                }
                // Defensive fallback: if no anchors found (floating island), use bottom row
                // to prevent an empty anchor set from crashing downstream routing.
                if (fallbackAnchors.isEmpty()) {
                    BlockPos minC = island.getMinCorner();
                    for (BlockPos p : island.getMembers()) {
                        if (p.getY() == minC.getY()) { fallbackAnchors.add(p); break; }
                    }
                }

                HybridPhysicsRouter.Backend backend = router.route(
                        islandId, island.getMembers(), fallbackAnchors, currentEpoch);
                if (backend == HybridPhysicsRouter.Backend.FNO) {
                    OnnxPFSFRuntime onnx = router.getOnnxRuntime();
                    if (onnx != null) {
                        OnnxPFSFRuntime.InferenceResult mlResult = onnx.infer(island);
                        if (mlResult != null) {
                            // ML succeeded — apply results directly, skip GPU iterations
                            applyMLResult(mlResult, island, level);
                            StructureIslandRegistry.markProcessed(islandId);
                            continue;  // skip PFSF GPU path for this island
                        }
                    }
                    // ML failed — fall through to PFSF
                }
            }

            // v0.2a: touch LRU for this island
            evictor.touchIsland(islandId);

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            if (buf == null) continue;  // v0.2a: VRAM rejected by ComputeRangePolicy

            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) {
                // all frames in flight — submit any accumulated batch before breaking
                if (!batch.isEmpty()) {
                    PFSFAsyncCompute.submitBatch(batch, callbacks);
                    batch.clear();
                    callbacks.clear();
                }
                break;
            }
            frame.islandId = islandId;

            // Data upload
            PFSFEngine.UploadContext ctx = new PFSFEngine.UploadContext(island, level,
                    materialLookup, anchorLookup, fillRatioLookup, curingLookup, currentWindVec);
            dispatcher.handleDataUpload(frame, buf, sparse, ctx, descriptorPool);

            // ─── v3: LOD 物理（距離分級）───
            int lod = PFSFLODPolicy.computeLodLevel(buf, players);
            buf.setLodLevel(lod);
            buf.decrementWakeTicks();

            if (lod == PFSFConstants.LOD_DORMANT && buf.getWakeTicksRemaining() <= 0) {
                StructureIslandRegistry.markProcessed(islandId);
                continue;
            }

            // ─── v3: 收斂跳過（分級閾值）───
            float change = 0;
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                change = Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
            }

            if (change > 0 && change < CONVERGENCE_SKIP_THRESHOLD) {
                // Prevent stableTickCount integer overflow
                if (buf.getStableTickCount() < Integer.MAX_VALUE) {
                    buf.incrementStableCount();
                }
            } else if (change >= CONVERGENCE_SKIP_THRESHOLD) {
                buf.resetStableCount();
            }

            if (buf.getStableTickCount() > STABLE_TICK_SKIP_COUNT) {
                // Completely skip and keep wake ticks logic updated to not infinite sleep
                StructureIslandRegistry.markProcessed(islandId);
                continue; // 完全跳過：已穩定 3+ tick
            }

            // ─── 步數計算（VRAM + LOD + 收斂 + early termination）───
            int baseSteps = PFSFScheduler.recommendSteps(buf, false, false);
            int steps = ComputeRangePolicy.adjustSteps(baseSteps, vramMgr);
            steps = PFSFLODPolicy.adjustStepsForLod(steps, lod);

            // v3: 收斂中減半
            if (change > 0 && change < CONVERGENCE_REDUCE_THRESHOLD) {
                steps = Math.max(1, steps / 2);
            }

            // v3: early termination（歷史趨勢預縮減）
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                if (change < EARLY_TERM_TIGHT) {
                    steps = Math.max(1, steps / 4);
                } else if (change < EARLY_TERM_LOOSE) {
                    steps = Math.max(2, steps / 2);
                }
            }

            // v3: macro-block skip 整合
            if (buf.cachedMacroResiduals != null) {
                float activeRatio = PFSFScheduler.getActiveRatio(buf.cachedMacroResiduals);
                if (activeRatio < 0.1f) steps = Math.max(1, steps / 4);
                else if (activeRatio < 0.5f) steps = Math.max(1, steps / 2);
            }

            try {
                dispatcher.recordSolveSteps(frame.cmdBuf, buf, steps, descriptorPool);

                if (steps > 0) {
                    // Barrier: solve steps write phi/stress buffers; phase-field reads them.
                    // Without this barrier, phase-field evolution may read incomplete solver output.
                    VulkanComputeContext.computeBarrier(frame.cmdBuf);

                    // v3: phase-field 條件更新（穩定時跳過）
                    if (buf.getStableTickCount() <= STABLE_TICK_PHASE_FIELD_SKIP) {
                        dispatcher.recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
                    }

                    // Barrier: phase-field writes damage field; failure detection reads it.
                    // Without this barrier, failure scan may read stale phase-field data.
                    VulkanComputeContext.computeBarrier(frame.cmdBuf);

                    dispatcher.recordFailureDetection(frame, buf, descriptorPool);
                }
            } catch (Exception e) {
                LOGGER.error("[PFSF] GPU recording failed for island {}: {}", islandId, e.getMessage());
                // Don't submit this frame — return it to pool
                continue;
            }

            buf.retain();
            final PFSFIslandBuffer finalBuf = buf;

            batch.add(frame);
            callbacks.add(() -> {
                try {
                    resultProcessor.processCompletedFrame(frame, finalBuf, level);
                } finally {
                    finalBuf.release();
                }
            });

            StructureIslandRegistry.markProcessed(islandId);

            // v0.2a: batch limit = 3 (ping-pong parallel)
            if (batch.size() >= 3) {
                PFSFAsyncCompute.submitBatch(batch, callbacks);
                batch.clear();
                callbacks.clear();
            }
        }

        // Submit remaining batch
        if (!batch.isEmpty()) {
            PFSFAsyncCompute.submitBatch(batch, callbacks);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public API
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

        // v3: 方塊變化 或 負載變化 → 重置收斂計數 + 喚醒 DORMANT + 更新拓撲版本
        buf.resetStableCount();
        if (buf.getLodLevel() == LOD_DORMANT) {
            buf.setWakeTicksRemaining(LOD_WAKE_TICKS);
        }
        // 結構變化（方塊增減）→ BFS 快取失效
        // wasAir: use CPU-side lastKnownTypes cache in PFSFSparseUpdate.
        // !buf.contains(pos) was always false here (early-return above guarantees buf.contains(pos)),
        // so we instead check the last type written for this position via sparse updates.
        // Defaults to VOXEL_AIR for unseen positions (correct: first placement is a topology change).
        int flatIdx = buf.flatIndex(pos);
        boolean wasAir = (sparse.getLastKnownType(flatIdx) == VOXEL_AIR);
        if (newMaterial == null || wasAir) {
            buf.incrementTopologyVersion();
        }
        float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;
        float source = newMaterial != null
                ? (float) (newMaterial.getDensity() * fillRatio * GRAVITY * BLOCK_VOLUME)
                : 0.0f;
        byte type = newMaterial == null ? VOXEL_AIR
                : (anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID);
        float maxPhi = newMaterial != null ? PFSFSourceBuilder.computeMaxPhi(newMaterial) : 0.0f;
        float rcomp  = newMaterial != null ? (float) newMaterial.getRcomp() : 0.0f;
        float rtens  = newMaterial != null ? (float) newMaterial.getRtens()  : 0.0f;

        sparse.markVoxelDirty(new PFSFSparseUpdate.VoxelUpdate(
                flatIdx, source, type, maxPhi, rcomp, rtens, new float[6]));
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
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

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

    StressField extractStressField(PFSFIslandBuffer buf) {
        return PFSFStressExtractor.extractStressField(buf);
    }

    @Override
    public void removeBuffer(int islandId) {
        PFSFBufferManager.removeBuffer(islandId);
    }

    // ═══ BIFROST: Apply ML inference result to game world ═══

    private void applyMLResult(OnnxPFSFRuntime.InferenceResult result,
                               StructureIslandRegistry.StructureIsland island,
                               ServerLevel level) {
        for (net.minecraft.core.BlockPos pos : island.getMembers()) {
            // Use per-block Rcomp from materialLookup (same as PFSFDataBuilder).
            // Fallback to 30 MPa (concrete) only when lookup is unavailable.
            float rcompMPa = 30.0f;
            if (materialLookup != null) {
                com.blockreality.api.material.RMaterial mat = materialLookup.apply(pos);
                if (mat != null) rcompMPa = (float) mat.getRcomp();
            }
            float ratio = result.getStressRatio(pos, rcompMPa);

            // Check failure (same thresholds as PFSF failure_scan)
            if (ratio > 1.0f) {
                com.blockreality.api.physics.FailureType type =
                        ratio > 2.0f ? com.blockreality.api.physics.FailureType.CRUSHING
                                     : com.blockreality.api.physics.FailureType.TENSION_BREAK;
                com.blockreality.api.collapse.CollapseManager.triggerPFSFCollapse(level, pos, type);
            }
        }
    }
}
