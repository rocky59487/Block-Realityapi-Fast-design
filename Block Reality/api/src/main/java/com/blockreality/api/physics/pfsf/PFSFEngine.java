package com.blockreality.api.physics.pfsf;

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
    /** ★ Performance fix (P0-003): reset every 20 ticks (1 s) instead of every tick. */
    private static int descriptorResetCountdown = 0;
    private static final int DESCRIPTOR_RESET_INTERVAL = 20;

    // ─── Material lookup (set by Mod initialization) ───
    private static Function<BlockPos, RMaterial> materialLookup;
    private static Function<BlockPos, Boolean> anchorLookup;
    private static Function<BlockPos, Float> fillRatioLookup;
    // v2.1: 固化時間效應 + 風向
    private static Function<BlockPos, Float> curingLookup = null;
    private static net.minecraft.world.phys.Vec3 currentWindVec = null;

    // ─── P2 重構：委託組件 ───
    private static final PFSFResultProcessor resultProcessor = new PFSFResultProcessor();
    private static final PFSFDispatcher dispatcher = new PFSFDispatcher();

    /** 資料上傳所需的上下文（P2：避免 PFSFDispatcher 直接存取 static fields） */
    static final class UploadContext {
        final StructureIsland island;
        final ServerLevel level;
        final Function<BlockPos, RMaterial> materialLookup;
        final Function<BlockPos, Boolean> anchorLookup;
        final Function<BlockPos, Float> fillRatioLookup;
        final Function<BlockPos, Float> curingLookup;
        final net.minecraft.world.phys.Vec3 windVec;

        UploadContext(StructureIsland island, ServerLevel level,
                      Function<BlockPos, RMaterial> mat, Function<BlockPos, Boolean> anchor,
                      Function<BlockPos, Float> fill, Function<BlockPos, Float> curing,
                      net.minecraft.world.phys.Vec3 wind) {
            this.island = island; this.level = level;
            this.materialLookup = mat; this.anchorLookup = anchor;
            this.fillRatioLookup = fill; this.curingLookup = curing; this.windVec = wind;
        }
    }

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

        // ★ Performance fix (P0-003): reset descriptor pool every 20 ticks (1 s) instead of
        // every tick. Unconditional per-tick reset caused GPU pipeline stalls (FPS -15~25).
        if (--descriptorResetCountdown <= 0) {
            VulkanComputeContext.resetDescriptorPool(descriptorPool);
            descriptorResetCountdown = DESCRIPTOR_RESET_INTERVAL;
        }

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

            // Phase 3: 稀疏更新或全量重建（P2：委託 PFSFDispatcher）
            UploadContext ctx = new UploadContext(island, level,
                    materialLookup, anchorLookup, fillRatioLookup, curingLookup, currentWindVec);
            dispatcher.handleDataUpload(frame, buf, sparse, ctx, descriptorPool);

            // ─── Phase 4: RBGS 迭代 + W-Cycle（v2.1：細網格改用 RBGS）───
            // v2: 自適應迭代 — 收斂 island 跳過（CPU 近似，省 90% ALU）
            if (buf.maxPhiPrev > 0 && buf.maxPhiPrevPrev > 0) {
                float change = Math.abs(buf.maxPhiPrev - buf.maxPhiPrevPrev) / buf.maxPhiPrev;
                if (change < PFSFScheduler.MACRO_BLOCK_CONVERGENCE_THRESHOLD) {
                    StructureIslandRegistry.markProcessed(islandId);
                    continue;
                }
            }

            boolean hasCollapse = false;
            int steps = PFSFScheduler.recommendSteps(buf, false, hasCollapse);


            // P2 重構：委託 PFSFDispatcher 錄製 GPU 命令
            dispatcher.recordSolveSteps(frame.cmdBuf, buf, steps, descriptorPool);

            // Phase 4.5: Phase-Field Evolution（v2.1 Ambati 2015）
            if (steps > 0) {
                dispatcher.recordPhaseFieldEvolve(frame.cmdBuf, buf, descriptorPool);
            }

            // Phase 5: failure scan + compact readback
            if (steps > 0) {
                dispatcher.recordFailureDetection(frame, buf, descriptorPool);
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
     * 處理 GPU 完成的計算結果（P2 重構：委託 PFSFResultProcessor）。
     */
    private static void processCompletedFrame(PFSFAsyncCompute.ComputeFrame frame,
                                                PFSFIslandBuffer buf, ServerLevel level) {
        resultProcessor.processCompletedFrame(frame, buf, level);
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

    // v2.1: Phase-Field Evolve Dispatch — P2 重構：已移至 PFSFDispatcher.recordPhaseFieldEvolve()

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
