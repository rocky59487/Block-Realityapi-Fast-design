package com.blockreality.api.physics.fluid;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.spi.IFluidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.blockreality.api.physics.fluid.OnnxFluidRuntime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流體 GPU 引擎 — PFSF-Fluid 的主入口和 tick 迴圈協調器。
 *
 * <p>實作 {@link IFluidManager} SPI，在 ServerTick 中執行流體模擬。
 * 遵循 {@code PFSFEngine} 的 tick 預算管理和非同步管線模式。
 *
 * <h3>Tick 迴圈結構</h3>
 * <pre>
 * Phase 1: 輪詢 GPU 結果（非阻塞）
 * Phase 2: 迭代髒區域（受 tick 預算限制）
 *   2a: 取得計算幀
 *   2b: 上傳 / 稀疏更新
 *   2c: 記錄 Jacobi dispatch
 *   2d: 記錄壓力 + 邊界提取
 *   2e: 非阻塞提交
 * Phase 3: 提取邊界壓力給 PFSF（<0.5ms）
 * </pre>
 */
public class FluidGPUEngine implements IFluidManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidEngine");

    private static FluidGPUEngine instance;

    private boolean initialized = false;
    private boolean available = false;

    // 每區域的 GPU buffer
    private final ConcurrentHashMap<Integer, FluidRegionBuffer> gpuBuffers = new ConcurrentHashMap<>();

    // 邊界壓力快取（上一 tick 的結果，供 PFSF 查詢）
    // ★ Audit fix: 使用 AtomicReference 確保引用替換的原子性，避免讀寫線程看到半構造的 Map。
    private final AtomicReference<Map<BlockPos, Float>> boundaryPressureCache =
        new AtomicReference<>(new ConcurrentHashMap<>());

    // ML fluid runtime (injected after mod init)
    @Nullable
    private OnnxFluidRuntime mlFluidRuntime;

    /** Maximum sub-cell count for ML path — 40³ = 64 000 (≈ 4 blocks × 4 × 4). */
    private static final int ML_MAX_SUB_CELLS = 64_000;

    // 描述子池重置計數器（每 20 tick 重置一次，照 PFSFEngine P0-003）
    private int descriptorResetCountdown = 0;
    private static final int DESCRIPTOR_RESET_INTERVAL = 20;

    private FluidGPUEngine() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static FluidGPUEngine getInstance() {
        if (instance == null) {
            instance = new FluidGPUEngine();
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════
    //  IFluidManager SPI 實作
    // ═══════════════════════════════════════════════════════

    @Override
    public void init(@Nonnull ServerLevel level) {
        if (initialized) return;

        // 檢查 Vulkan 可用性
        available = com.blockreality.api.physics.pfsf.VulkanComputeContext.isAvailable();

        if (available) {
            FluidPipelineFactory.createAll();
            LOGGER.info("[BR-FluidEngine] GPU fluid engine initialized");
        } else {
            LOGGER.warn("[BR-FluidEngine] Vulkan unavailable, falling back to CPU solver");
        }

        initialized = true;
    }

    @Override
    public void tick(@Nonnull ServerLevel level, int tickBudgetMs) {
        if (!BRConfig.isFluidEnabled()) return;
        // 懶初始化：首次 tick 時建立 Vulkan pipeline（VulkanComputeContext 已在 ServerStartingEvent 初始化）
        if (!initialized) {
            init(level);
        }

        long startNanos = System.nanoTime();

        // ─── Phase 1: 輪詢 GPU 結果 ───
        if (available) {
            FluidAsyncCompute.pollCompleted();
        }

        // ─── Phase 2: 描述子池重置 ───
        if (--descriptorResetCountdown <= 0) {
            // 實際 Vulkan：vkResetDescriptorPool()
            descriptorResetCountdown = DESCRIPTOR_RESET_INTERVAL;
        }

        // ─── Phase 3: 迭代髒區域 ───
        FluidRegionRegistry registry = FluidRegionRegistry.getInstance();
        for (FluidRegion region : registry.getActiveRegions()) {
            // 檢查 tick 預算
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (elapsedMs >= tickBudgetMs) break;

            if (!region.isDirty()) continue;

            // ML > GPU NS > CPU Stable Fluids
            if (shouldUseMLFor(region)) {
                tickRegionML(region);
            } else if (available) {
                tickRegionGPU(region);
            } else {
                tickRegionCPU(region);
            }
        }

        // ─── Phase 4: 提取邊界壓力 ───
        boundaryPressureCache.set(FluidPressureCoupler.extractAllBoundaryPressures(registry));
    }

    /**
     * GPU 路徑：記錄 Jacobi + 壓力 + 邊界 dispatch 並非同步提交。
     */
    private void tickRegionGPU(FluidRegion region) {
        FluidAsyncCompute.FluidComputeFrame frame = FluidAsyncCompute.acquireFrame();
        if (frame == null) return; // 所有幀都在飛行中

        FluidRegionBuffer buf = getOrCreateBuffer(region);
        if (buf == null) {
            // VRAM 不足，回退到 CPU 運算
            tickRegionCPU(region);
            return;
        }
        frame.regionId = region.getRegionId();

        // 上傳髒資料
        if (buf.isDirty()) {
            buf.uploadFromCPU(region);
        }

        // 記錄 Jacobi 迭代 dispatch
        int iterations = FluidConstants.DEFAULT_ITERATIONS_PER_TICK;
        FluidJacobiRecorder.recordJacobiIterations(frame, buf, iterations);

        // 記錄壓力 + 速度場計算
        FluidJacobiRecorder.recordPressureCompute(frame, buf);

        // 記錄邊界壓力提取
        FluidJacobiRecorder.recordBoundaryExtraction(frame, buf);

        // 非阻塞提交
        buf.retain();
        FluidAsyncCompute.submitAsync(frame, v -> {
            try {
                processCompletedFrame(frame, buf, region);
            } finally {
                buf.release();
            }
        });

        region.clearDirty();
    }

    /**
     * CPU 回退路徑：使用 RBGS 求解（比 Jacobi 快 ~2×）。
     */
    private void tickRegionCPU(FluidRegion region) {
        FluidCPUSolver.rbgsSolve(region,
            FluidConstants.DEFAULT_ITERATIONS_PER_TICK,
            FluidConstants.DEFAULT_DIFFUSION_RATE);
        region.clearDirty();
    }

    /**
     * Inject the ML fluid runtime (called from mod init after BIFROSTModelRegistry.init()).
     */
    public void setMLRuntime(@Nullable OnnxFluidRuntime r) {
        this.mlFluidRuntime = r;
    }

    /**
     * Route to ML path if the FNOFluid3D model is ready and the region fits within the
     * model's expected block count (sub-cells ≤ ML_MAX_SUB_CELLS = 40³ = 64 000).
     */
    private boolean shouldUseMLFor(FluidRegion region) {
        if (mlFluidRuntime == null || !mlFluidRuntime.isReady()) return false;
        int subCells = region.getSizeX() * FluidRegion.SUB
                     * region.getSizeY() * FluidRegion.SUB
                     * region.getSizeZ() * FluidRegion.SUB;
        return subCells <= ML_MAX_SUB_CELLS
            && region.getSizeX() == mlFluidRuntime.getBlockL()
            && region.getSizeY() == mlFluidRuntime.getBlockL()
            && region.getSizeZ() == mlFluidRuntime.getBlockL();
    }

    /**
     * ML 推理路徑：FNOFluid3D ONNX infer → write block-averaged p/vx/vy/vz back to region.
     * Falls back to CPU on inference failure.
     */
    private void tickRegionML(FluidRegion region) {
        OnnxFluidRuntime.FluidInferenceResult result = mlFluidRuntime.infer(region);
        if (result == null) {
            // Inference failed — fall back to CPU
            tickRegionCPU(region);
            return;
        }

        int L = result.getL();
        float[] subPressure = region.getSubPressure();
        float[] vxArr       = region.getVx();
        float[] vyArr       = region.getVy();
        float[] vzArr       = region.getVz();
        float[] blockPres   = region.getPressure();

        for (int bz = 0; bz < L; bz++) {
            for (int by = 0; by < L; by++) {
                for (int bx = 0; bx < L; bx++) {
                    float p  = result.getPressure(bx, by, bz);
                    float vx = result.getVx(bx, by, bz);
                    float vy = result.getVy(bx, by, bz);
                    float vz = result.getVz(bx, by, bz);

                    // Broadcast block-averaged values into sub-cell arrays
                    for (int sz = 0; sz < FluidRegion.SUB; sz++) {
                        for (int sy = 0; sy < FluidRegion.SUB; sy++) {
                            for (int sx = 0; sx < FluidRegion.SUB; sx++) {
                                int sIdx = region.subFlat(bx, by, bz, sx, sy, sz);
                                subPressure[sIdx] = p;
                                vxArr[sIdx]       = vx;
                                vyArr[sIdx]       = vy;
                                vzArr[sIdx]       = vz;
                            }
                        }
                    }

                    // Write block-level pressure so FluidPressureCoupler can read it unchanged
                    int bIdx = bx + by * L + bz * L * L;
                    blockPres[bIdx] = p;
                }
            }
        }
        region.clearDirty();
    }

    /**
     * 處理 GPU 完成的計算幀。
     */
    private void processCompletedFrame(FluidAsyncCompute.FluidComputeFrame frame,
                                       FluidRegionBuffer buf, FluidRegion region) {
        // 讀回邊界壓力並同步到 CPU 側 FluidRegion
        buf.asyncReadBoundaryPressure(pressureData -> {
            FluidRegion r = FluidRegionRegistry.getInstance().getRegionById(buf.getRegionId());
            if (r == null) return;
            float[] cpuPressure = r.getPressure();
            System.arraycopy(pressureData, 0, cpuPressure, 0,
                Math.min(pressureData.length, cpuPressure.length));
            r.markDirty();

            // 更新邊界壓力快取供 PFSF 查詢
            Map<BlockPos, Float> newCache = new java.util.HashMap<>();
            Map<BlockPos, Float> old = boundaryPressureCache.get();
            if (old != null) newCache.putAll(old);
            BlockPos origin = buf.getOrigin();
            if (origin != null) {
                // 儲存 region 代表點壓力（取最大值，供 FluidPressureCoupler 使用）
                float maxP = 0f;
                for (float p : pressureData) if (p > maxP) maxP = p;
                newCache.put(origin, maxP);
            }
            boundaryPressureCache.set(new ConcurrentHashMap<>(newCache));
        });

        // 收斂追蹤
        // float maxPhi = readMaxPhiFromStaging(frame);
        // buf.updateMaxPhi(maxPhi);
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        if (available) {
            FluidAsyncCompute.shutdown();
            FluidPipelineFactory.destroyAll();
        }

        gpuBuffers.values().forEach(FluidRegionBuffer::free);
        gpuBuffers.clear();
        FluidRegionRegistry.getInstance().clear();

        initialized = false;
        LOGGER.info("[BR-FluidEngine] Shutdown complete");
    }

    @SubscribeEvent
    public void onBarrierBreach(com.blockreality.api.event.FluidBarrierBreachEvent event) {
        notifyBarrierBreachBatch(event.getBreachedPositions());
    }

    // ═══════════════════════════════════════════════════════
    //  查詢介面
    // ═══════════════════════════════════════════════════════

    @Override
    public float getFluidPressureAt(@Nonnull BlockPos pos) {
        Float pressure = boundaryPressureCache.get().get(pos);
        if (pressure != null) return pressure;

        // 回退：從 CPU 端 FluidRegion 查詢
        FluidRegion region = FluidRegionRegistry.getInstance()
            .getRegion(pos, BRConfig.getFluidMaxRegionSize());
        if (region != null) {
            FluidState state = region.getFluidStateAt(pos);
            return state.pressure();
        }
        return 0f;
    }

    @Override
    public float getFluidVolumeAt(@Nonnull BlockPos pos) {
        FluidRegion region = FluidRegionRegistry.getInstance()
            .getRegion(pos, BRConfig.getFluidMaxRegionSize());
        if (region != null) {
            FluidState state = region.getFluidStateAt(pos);
            return state.volume();
        }
        return 0f;
    }

    @Override
    public void notifyBarrierBreach(@Nonnull BlockPos pos) {
        int regionSize = BRConfig.getFluidMaxRegionSize();
        FluidRegion region = FluidRegionRegistry.getInstance().getRegion(pos, regionSize);
        if (region != null) {
            int idx = region.flatIndex(pos);
            if (idx >= 0) {
                // 將崩塌的固體方塊轉為空氣，讓流體可以湧入
                region.setVoxelType(idx, FluidType.AIR);
                region.markDirty();
            }
        }
    }

    /**
     * 批次處理多個崩塌位置：按區域分組、一次性標記 dirty，避免重複 GPU 上傳。
     */
    @Override
    public void notifyBarrierBreachBatch(@Nonnull java.util.Collection<BlockPos> positions) {
        if (positions.isEmpty()) return;
        int regionSize = BRConfig.getFluidMaxRegionSize();
        FluidRegionRegistry registry = FluidRegionRegistry.getInstance();

        // 已標記 dirty 的區域集合（避免重複呼叫 markDirty）
        Set<FluidRegion> dirtyRegions = ConcurrentHashMap.newKeySet();

        for (BlockPos pos : positions) {
            FluidRegion region = registry.getRegion(pos, regionSize);
            if (region == null) continue;
            int idx = region.flatIndex(pos);
            if (idx >= 0) {
                region.setVoxelType(idx, FluidType.AIR);
                dirtyRegions.add(region);
            }
        }

        // 統一標記 dirty，下個 tick GPU upload 時才重整
        for (FluidRegion r : dirtyRegions) {
            r.markDirty();
            FluidRegionBuffer buf = gpuBuffers.get(r.getRegionId());
            if (buf != null) buf.markDirty();
        }

        LOGGER.debug("[BR-FluidEngine] Barrier breach batch: {} positions, {} regions affected",
            positions.size(), dirtyRegions.size());
    }

    @Override
    public void setFluidSource(@Nonnull BlockPos pos, int type, float volume) {
        int regionSize = BRConfig.getFluidMaxRegionSize();
        FluidRegion region = FluidRegionRegistry.getInstance()
            .getOrCreateRegion(pos, regionSize);
        int idx = region.flatIndex(pos);
        if (idx >= 0) {
            FluidType ft = FluidType.fromId(type);
            float density = (float) ft.getDensity();
            float g = (float) FluidConstants.GRAVITY;
            float height = pos.getY();
            float phi = density * g * height * volume;
            float pressure = phi;
            region.setFluidState(idx, ft, volume, phi, pressure);
            region.markDirty();
        }
    }

    @Override
    public void removeFluid(@Nonnull BlockPos pos) {
        int regionSize = BRConfig.getFluidMaxRegionSize();
        FluidRegion region = FluidRegionRegistry.getInstance().getRegion(pos, regionSize);
        if (region != null) {
            int idx = region.flatIndex(pos);
            if (idx >= 0) {
                region.setFluidState(idx, FluidType.AIR, 0f, 0f, 0f);
                region.markDirty();
            }
        }
    }

    @Override
    public int getActiveRegionCount() {
        return FluidRegionRegistry.getInstance().getRegionCount();
    }

    @Override
    public int getTotalFluidVoxelCount() {
        int total = 0;
        for (FluidRegion region : FluidRegionRegistry.getInstance().getActiveRegions()) {
            total += region.getFluidVoxelCount();
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════
    //  內部工具
    // ═══════════════════════════════════════════════════════

    /**
     * 取得邊界壓力快取，供 PFSF 結構引擎查詢。
     */
    @Nonnull
    public Map<BlockPos, Float> getBoundaryPressureCache() {
        return boundaryPressureCache.get();
    }

    private FluidRegionBuffer getOrCreateBuffer(FluidRegion region) {
        return FluidBufferManager.getOrCreate(region);
    }

    /** Returns the GPU buffer for a given region, or null if not yet allocated. */
    @Nullable
    public FluidRegionBuffer getGpuBufferFor(int regionId) {
        return gpuBuffers.get(regionId);
    }

    public static boolean isAvailable() {
        return instance != null && instance.available;
    }
}
