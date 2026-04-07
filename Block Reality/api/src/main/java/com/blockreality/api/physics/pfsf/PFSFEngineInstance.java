package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.RMaterial;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 引擎實例 — P0 重構：所有狀態從 static 移到實例欄位。
 *
 * <p>此類別持有原 PFSFEngine 的全部可變狀態，包括：
 * <ul>
 *   <li>初始化/可用狀態</li>
 *   <li>Descriptor Pool</li>
 *   <li>Material/Anchor/FillRatio/Curing/Wind lookups</li>
 *   <li>PFSFResultProcessor、PFSFDispatcher 委託</li>
 * </ul>
 *
 * <p>優點：
 * <ul>
 *   <li>可測試（注入 mock lookups、mock VulkanContext）</li>
 *   <li>多世界支援（每個 ServerLevel 一個 instance）</li>
 *   <li>乾淨關閉（instance GC 後無狀態殘留）</li>
 * </ul>
 *
 * <p>{@link PFSFEngine} 保留 static facade，委託給 singleton instance。
 */
public final class PFSFEngineInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    // ─── State ───
    private boolean initialized = false;
    private boolean available = false;

    // ─── Descriptor Pool（v3: on-demand reset） ───
    private DescriptorPoolManager descriptorPoolMgr;

    // ─── v3: VRAM-aware island management ───
    private final IslandBufferEvictor evictor = new IslandBufferEvictor();
    private int tickCounter = 0;

    // ─── Material lookup ───
    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;
    private Function<BlockPos, Float> fillRatioLookup;
    private Function<BlockPos, Float> curingLookup;
    private Vec3 currentWindVec;

    // ─── P2 委託組件 ───
    private final PFSFResultProcessor resultProcessor = new PFSFResultProcessor();
    private final PFSFDispatcher dispatcher = new PFSFDispatcher();

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

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
            LOGGER.error("[PFSF] Engine init failed: {}", e.getMessage());
            available = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main Tick Loop
    // ═══════════════════════════════════════════════════════════════

    public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;

        PFSFAsyncCompute.pollCompleted();

        // v3: on-demand descriptor pool reset
        descriptorPoolMgr.tickResetIfNeeded();

        // v3: LRU evictor tick + periodic check
        evictor.tick();
        tickCounter++;
        if (tickCounter % evictor.getCheckInterval() == 0) {
            evictor.evictIfNeeded(VulkanComputeContext.getVramBudgetManager());
        }

        long descriptorPool = descriptorPoolMgr.getPool();
        VramBudgetManager vramMgr = VulkanComputeContext.getVramBudgetManager();
        long startTime = System.nanoTime();

        // v3: Ping-Pong parallel — collect frames into batch (max 3)
        List<PFSFAsyncCompute.ComputeFrame> batch = new ArrayList<>(3);
        List<Consumer<Void>> callbacks = new ArrayList<>(3);

        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(currentEpoch).entrySet()) {

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= BRConfig.getPFSFTickBudgetMs()) break;

            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;

            // v3: touch LRU for this island
            evictor.touchIsland(islandId);

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            if (buf == null) continue;  // v3: VRAM rejected by ComputeRangePolicy

            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) break;  // all frames in flight
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
                buf.incrementStableCount();
            } else if (change >= CONVERGENCE_SKIP_THRESHOLD) {
                buf.resetStableCount();
            }

            if (buf.getStableTickCount() > STABLE_TICK_SKIP_COUNT) {
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

            dispatcher.recordSolveSteps(frame.cmdBuf, buf, steps, descriptorPool);

            if (steps > 0) {
                // v3: phase-field 條件更新（穩定時跳過）
                if (buf.getStableTickCount() <= STABLE_TICK_PHASE_FIELD_SKIP) {
                    dispatcher.recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
                }
                dispatcher.recordFailureDetection(frame, buf, descriptorPool);
            }

            buf.retain();
            final PFSFIslandBuffer finalBuf = buf;

            batch.add(frame);
            callbacks.add(v -> {
                try {
                    resultProcessor.processCompletedFrame(frame, finalBuf, level);
                } finally {
                    finalBuf.release();
                }
            });

            StructureIslandRegistry.markProcessed(islandId);

            // v3: batch limit = 3 (ping-pong parallel)
            if (batch.size() >= 3) {
                PFSFAsyncCompute.submitBatch(batch, callbacks);
                batch = new ArrayList<>(3);
                callbacks = new ArrayList<>(3);
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

    public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial,
                                   Set<BlockPos> anchors) {
        PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                islandId, PFSFSparseUpdate::new);
        PFSFIslandBuffer buf = PFSFBufferManager.buffers.get(islandId);

        if (buf == null || !buf.contains(pos)) {
            sparse.markFullRebuild();
            return;
        }

        // v3: 方塊變化 → 重置收斂計數 + 喚醒 DORMANT + 更新拓撲版本
        buf.resetStableCount();
        if (buf.getLodLevel() == LOD_DORMANT) {
            buf.setWakeTicksRemaining(LOD_WAKE_TICKS);
        }
        // 結構變化（方塊增減）→ BFS 快取失效
        byte oldType = newMaterial == null ? VOXEL_AIR : VOXEL_SOLID;
        boolean wasAir = !buf.contains(pos); // 近似判斷
        if (newMaterial == null || wasAir) {
            buf.incrementTopologyVersion();
        }

        int flatIdx = buf.flatIndex(pos);
        float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;
        float source = newMaterial != null
                ? (float) (newMaterial.getDensity() * fillRatio * GRAVITY * BLOCK_VOLUME)
                : 0.0f;
        byte type = newMaterial == null ? VOXEL_AIR
                : (anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID);
        float maxPhi = newMaterial != null ? PFSFSourceBuilder.computeMaxPhi(newMaterial) : 0.0f;
        float rcomp = newMaterial != null ? (float) newMaterial.getRcomp() : 0.0f;

        sparse.markVoxelDirty(new PFSFSparseUpdate.VoxelUpdate(
                flatIdx, source, type, maxPhi, rcomp, new float[6]));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════════════════════════

    public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) { this.materialLookup = lookup; }
    public void setAnchorLookup(Function<BlockPos, Boolean> lookup) { this.anchorLookup = lookup; }
    public void setFillRatioLookup(Function<BlockPos, Float> lookup) { this.fillRatioLookup = lookup; }
    public void setCuringLookup(Function<BlockPos, Float> lookup) { this.curingLookup = lookup; }
    public void setWindVector(Vec3 wind) { this.currentWindVec = wind; }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    long getDescriptorPool() { return descriptorPoolMgr != null ? descriptorPoolMgr.getPool() : 0; }
    public boolean isAvailable() { return available; }

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

    public void removeBuffer(int islandId) {
        PFSFBufferManager.removeBuffer(islandId);
    }
}
