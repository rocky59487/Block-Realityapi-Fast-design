package com.blockreality.api.physics.fluid;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.spi.IFluidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private volatile Map<BlockPos, Float> boundaryPressureCache = new ConcurrentHashMap<>();

    // 描述子池重置計數器（每 20 tick 重置一次，照 PFSFEngine P0-003）
    private int descriptorResetCountdown = 0;
    private static final int DESCRIPTOR_RESET_INTERVAL = 20;

    private FluidGPUEngine() {}

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
        // available = VulkanComputeContext.isAvailable();
        available = true; // placeholder

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
        if (!initialized || !BRConfig.isFluidEnabled()) return;

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

            if (available) {
                tickRegionGPU(region);
            } else {
                tickRegionCPU(region);
            }
        }

        // ─── Phase 4: 提取邊界壓力 ───
        boundaryPressureCache = FluidPressureCoupler.extractAllBoundaryPressures(registry);
    }

    /**
     * GPU 路徑：記錄 Jacobi + 壓力 + 邊界 dispatch 並非同步提交。
     */
    private void tickRegionGPU(FluidRegion region) {
        FluidAsyncCompute.FluidComputeFrame frame = FluidAsyncCompute.acquireFrame();
        if (frame == null) return; // 所有幀都在飛行中

        FluidRegionBuffer buf = getOrCreateBuffer(region);
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
     * CPU 回退路徑：直接在主執行緒執行 Jacobi。
     */
    private void tickRegionCPU(FluidRegion region) {
        FluidCPUSolver.solve(region,
            FluidConstants.DEFAULT_ITERATIONS_PER_TICK,
            FluidConstants.DEFAULT_DIFFUSION_RATE);
        region.clearDirty();
    }

    /**
     * 處理 GPU 完成的計算幀。
     */
    private void processCompletedFrame(FluidAsyncCompute.FluidComputeFrame frame,
                                       FluidRegionBuffer buf, FluidRegion region) {
        // 讀回邊界壓力
        buf.asyncReadBoundaryPressure(pressureData -> {
            // 更新 CPU 側 FluidRegion 的壓力資料
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

    // ═══════════════════════════════════════════════════════
    //  查詢介面
    // ═══════════════════════════════════════════════════════

    @Override
    public float getFluidPressureAt(@Nonnull BlockPos pos) {
        Float pressure = boundaryPressureCache.get(pos);
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
                region.setFluidState(idx, FluidType.AIR, 0f, 0f, 0f);
                region.markDirty();
            }
        }
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
        return boundaryPressureCache;
    }

    private FluidRegionBuffer getOrCreateBuffer(FluidRegion region) {
        return gpuBuffers.computeIfAbsent(region.getRegionId(), id -> {
            FluidRegionBuffer buf = new FluidRegionBuffer(id);
            buf.allocate(region.getSizeX(), region.getSizeY(), region.getSizeZ(),
                new BlockPos(region.getOriginX(), region.getOriginY(), region.getOriginZ()));
            return buf;
        });
    }

    public static boolean isAvailable() {
        return instance != null && instance.available;
    }
}
