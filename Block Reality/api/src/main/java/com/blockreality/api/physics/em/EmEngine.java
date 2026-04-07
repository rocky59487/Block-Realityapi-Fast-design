package com.blockreality.api.physics.em;

import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DiffusionRegionRegistry;
import com.blockreality.api.physics.solver.DiffusionSolver;
import com.blockreality.api.spi.IElectromagneticManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 電磁場引擎 — 委託 DiffusionSolver 求解電位 Poisson 方程。
 *
 * <p>∇²φ = -ρ_charge/ε → 電位場 → E = -∇φ → J = σE → P = J²/σ (Joule)
 */
public class EmEngine implements IElectromagneticManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-EmEngine");
    private static EmEngine instance;

    private boolean initialized = false;
    private final EmTranslator translator = new EmTranslator();
    private final DiffusionRegionRegistry registry = new DiffusionRegionRegistry("em");

    private EmEngine() {}

    public static EmEngine getInstance() {
        if (instance == null) instance = new EmEngine();
        return instance;
    }

    @Override
    public void init(@Nonnull ServerLevel level) {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BR-EM] Electromagnetic engine initialized");
    }

    @Override
    public void tick(@Nonnull ServerLevel level, int tickBudgetMs) {
        if (!initialized) return;
        long start = System.nanoTime();

        for (DiffusionRegion region : registry.getActiveRegions()) {
            if ((System.nanoTime() - start) / 1_000_000 >= tickBudgetMs) break;
            if (!region.isDirty()) continue;

            DiffusionSolver.rbgsSolve(region,
                translator.getDefaultMaxIterations(),
                translator.getDefaultDiffusionRate(),
                translator.getGravityWeight());
            translator.interpretResults(region);
            region.clearDirty();
        }
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        registry.clear();
        initialized = false;
        LOGGER.info("[BR-EM] Shutdown complete");
    }

    @Override
    public float getElectricPotentialAt(@Nonnull BlockPos pos) {
        DiffusionRegion r = registry.getRegion(pos, EmConstants.DEFAULT_REGION_SIZE);
        if (r != null) { int idx = r.flatIndex(pos); if (idx >= 0) return r.getPhi()[idx]; }
        return 0f;
    }

    @Override
    public float getCurrentDensityAt(@Nonnull BlockPos pos) {
        // J = σ × |E| = σ × |∇φ|（需計算梯度，簡化版）
        return 0f; // TODO: implement gradient magnitude
    }

    @Override
    public void setChargeSource(@Nonnull BlockPos pos, float chargeDensity) {
        DiffusionRegion r = registry.getOrCreateRegion(pos, EmConstants.DEFAULT_REGION_SIZE);
        int idx = r.flatIndex(pos);
        if (idx >= 0) {
            r.getSource()[idx] = -chargeDensity / (float) EmConstants.PERMITTIVITY_FREE_SPACE;
            r.getPhi()[idx] = chargeDensity > 0 ? EmConstants.MIN_LIGHTNING_POTENTIAL * 10 : 0f;
            r.getType()[idx] = DiffusionRegion.TYPE_ACTIVE;
            r.markDirty();
        }
    }

    @Override
    public void setGroundPoint(@Nonnull BlockPos pos) {
        DiffusionRegion r = registry.getOrCreateRegion(pos, EmConstants.DEFAULT_REGION_SIZE);
        int idx = r.flatIndex(pos);
        if (idx >= 0) {
            r.getPhi()[idx] = 0f;  // Dirichlet BC: φ=0
            r.getSource()[idx] = 0f;
            r.getType()[idx] = DiffusionRegion.TYPE_SOLID_WALL;  // 固定邊界
            r.markDirty();
        }
    }

    @Override
    @Nonnull
    public List<BlockPos> computeLightningPath(@Nonnull BlockPos start) {
        DiffusionRegion r = registry.getRegion(start, EmConstants.DEFAULT_REGION_SIZE);
        if (r == null) return List.of();
        return LightningPathfinder.findPath(r, start);
    }

    @Override
    public int getActiveRegionCount() { return registry.getRegionCount(); }

    public DiffusionRegionRegistry getRegistry() { return registry; }
}
