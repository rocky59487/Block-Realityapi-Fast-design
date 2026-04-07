package com.blockreality.api.physics.thermal;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DiffusionRegionRegistry;
import com.blockreality.api.physics.solver.DiffusionSolver;
import com.blockreality.api.spi.IThermalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熱傳導引擎 — 薄包裝層，委託 DiffusionSolver 進行實際計算。
 *
 * <p>這是「共用求解器 + 轉譯層」架構的第一個域實作。
 * 引擎本身不包含任何求解邏輯，全部委託給
 * {@link DiffusionSolver}（CPU）或通用 GPU shader。
 *
 * <h3>Tick 流程</h3>
 * <pre>
 * 1. ThermalTranslator.populateRegion() — 從世界讀取熱源/材料屬性
 * 2. DiffusionSolver.rbgsSolve() — 通用 RBGS 擴散迭代
 * 3. ThermalTranslator.interpretResults() — 導出溫度場
 * 4. ThermalStressCalculator.extractThermalStresses() — 計算熱應力
 * </pre>
 */
public class ThermalEngine implements IThermalManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-ThermalEngine");

    private static ThermalEngine instance;

    private boolean initialized = false;
    private final ThermalTranslator translator = new ThermalTranslator();
    private final DiffusionRegionRegistry registry = new DiffusionRegionRegistry("thermal");

    // 熱應力快取（供 PFSF 下一 tick 查詢）
    private final AtomicReference<Map<BlockPos, Float>> thermalStressCache =
        new AtomicReference<>(Map.of());

    private ThermalEngine() {}

    public static ThermalEngine getInstance() {
        if (instance == null) instance = new ThermalEngine();
        return instance;
    }

    @Override
    public void init(@Nonnull ServerLevel level) {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BR-Thermal] Thermal engine initialized");
    }

    @Override
    public void tick(@Nonnull ServerLevel level, int tickBudgetMs) {
        if (!initialized) return;
        long start = System.nanoTime();

        for (DiffusionRegion region : registry.getActiveRegions()) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (elapsed >= tickBudgetMs) break;
            if (!region.isDirty()) continue;

            // 委託通用求解器
            DiffusionSolver.rbgsSolve(region,
                translator.getDefaultMaxIterations(),
                translator.getDefaultDiffusionRate(),
                translator.getGravityWeight());
            translator.interpretResults(region);
            region.clearDirty();
        }

        // 提取熱應力給 PFSF
        Map<BlockPos, Float> stresses = new java.util.HashMap<>();
        for (DiffusionRegion region : registry.getActiveRegions()) {
            stresses.putAll(ThermalStressCalculator.extractThermalStresses(region));
        }
        thermalStressCache.set(stresses);
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        registry.clear();
        initialized = false;
        LOGGER.info("[BR-Thermal] Shutdown complete");
    }

    @Override
    public float getTemperatureAt(@Nonnull BlockPos pos) {
        DiffusionRegion region = registry.getRegion(pos, ThermalConstants.DEFAULT_REGION_SIZE);
        if (region != null) {
            int idx = region.flatIndex(pos);
            if (idx >= 0) return region.getPhi()[idx];
        }
        return ThermalConstants.AMBIENT_TEMPERATURE;
    }

    @Override
    public void setHeatSource(@Nonnull BlockPos pos, float temperature) {
        DiffusionRegion region = registry.getOrCreateRegion(pos, ThermalConstants.DEFAULT_REGION_SIZE);
        int idx = region.flatIndex(pos);
        if (idx >= 0) {
            region.getPhi()[idx] = temperature;
            region.getSource()[idx] = (temperature - ThermalConstants.AMBIENT_TEMPERATURE) * 0.01f;
            region.getType()[idx] = DiffusionRegion.TYPE_ACTIVE;
            region.markDirty();
        }
    }

    @Override
    public void removeHeatSource(@Nonnull BlockPos pos) {
        DiffusionRegion region = registry.getRegion(pos, ThermalConstants.DEFAULT_REGION_SIZE);
        if (region != null) {
            int idx = region.flatIndex(pos);
            if (idx >= 0) {
                region.getSource()[idx] = 0f;
                region.markDirty();
            }
        }
    }

    @Override
    public int getActiveRegionCount() { return registry.getRegionCount(); }

    /** 取得熱應力快取，供 PFSF 耦合器查詢 */
    @Nonnull
    public Map<BlockPos, Float> getThermalStressCache() { return thermalStressCache.get(); }
}
