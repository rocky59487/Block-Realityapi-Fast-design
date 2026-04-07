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

import java.util.*;
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

    // ─── Descriptor Pool ───
    private long descriptorPool;
    private int descriptorResetCountdown = 0;
    private static final int DESCRIPTOR_RESET_INTERVAL = 20;

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
            descriptorPool = VulkanComputeContext.createIsolatedDescriptorPool(2048, 8192, "PFSF");
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

        if (--descriptorResetCountdown <= 0) {
            VulkanComputeContext.resetDescriptorPool(descriptorPool);
            descriptorResetCountdown = DESCRIPTOR_RESET_INTERVAL;
        }

        long startTime = System.nanoTime();

        for (Map.Entry<Integer, StructureIsland> entry :
                StructureIslandRegistry.getDirtyIslands(currentEpoch).entrySet()) {

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= BRConfig.getPFSFTickBudgetMs()) break;

            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();
            if (island == null) continue;
            if (island.getBlockCount() < 1 || island.getBlockCount() > BRConfig.getPFSFMaxIslandSize()) continue;

            PFSFIslandBuffer buf = PFSFBufferManager.getOrCreateBuffer(island);
            PFSFSparseUpdate sparse = PFSFBufferManager.sparseTrackers.computeIfAbsent(
                    islandId, PFSFSparseUpdate::new);

            PFSFAsyncCompute.ComputeFrame frame = PFSFAsyncCompute.acquireFrame();
            if (frame == null) continue;
            frame.islandId = islandId;

            // Data upload
            PFSFEngine.UploadContext ctx = new PFSFEngine.UploadContext(island, level,
                    materialLookup, anchorLookup, fillRatioLookup, curingLookup, currentWindVec);
            dispatcher.handleDataUpload(frame, buf, sparse, ctx, descriptorPool);

            // Convergence check
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                float change = Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
                if (change < PFSFScheduler.MACRO_BLOCK_CONVERGENCE_THRESHOLD) {
                    StructureIslandRegistry.markProcessed(islandId);
                    continue;
                }
            }

            int steps = PFSFScheduler.recommendSteps(buf, false, false);
            dispatcher.recordSolveSteps(frame.cmdBuf, buf, steps, descriptorPool);

            if (steps > 0) {
                dispatcher.recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
                dispatcher.recordFailureDetection(frame, buf, descriptorPool);
            }

            buf.retain();
            final PFSFIslandBuffer finalBuf = buf;
            PFSFAsyncCompute.submitAsync(frame, v -> {
                try {
                    resultProcessor.processCompletedFrame(frame, finalBuf, level);
                } finally {
                    finalBuf.release();
                }
            });

            StructureIslandRegistry.markProcessed(islandId);
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

    long getDescriptorPool() { return descriptorPool; }
    public boolean isAvailable() { return available; }

    public void shutdown() {
        if (!initialized) return;

        PFSFAsyncCompute.shutdown();
        PFSFBufferManager.freeAll();

        if (descriptorPool != 0) {
            VulkanComputeContext.destroyDescriptorPool(descriptorPool);
            descriptorPool = 0;
        }

        initialized = false;
        available = false;
        LOGGER.info("[PFSF] Engine shut down");
    }

    public String getStats() {
        if (!available) return "PFSF Engine: DISABLED";
        return String.format("PFSF Engine: %d islands buffered, %d total voxels",
                PFSFBufferManager.buffers.size(),
                PFSFBufferManager.buffers.values().stream().mapToInt(PFSFIslandBuffer::getN).sum());
    }

    StressField extractStressField(PFSFIslandBuffer buf) {
        return PFSFStressExtractor.extractStressField(buf);
    }

    public void removeBuffer(int islandId) {
        PFSFBufferManager.removeBuffer(islandId);
    }
}
