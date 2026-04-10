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
    private final EmThermalInjector thermalInjector = new EmThermalInjector();

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

        tickJouleHeating();
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        registry.clear();
        initialized = false;
        LOGGER.info("[BR-EM] Shutdown complete");
    }

    /**
     * Computes Joule heat P = J²/σ for all active EM regions and injects
     * hot spots above {@link EmConstants#JOULE_HEAT_THRESHOLD} into the
     * thermal injector for subsequent PFSF source coupling.
     *
     * <p>Called at the end of each {@link #tick} pass.
     */
    private void tickJouleHeating() {
        for (DiffusionRegion region : registry.getActiveRegions()) {
            float[] phi = region.getPhi();
            float[] sigma = region.getConductivity();
            int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();

            for (int iz = 0; iz < sz; iz++) {
                for (int iy = 0; iy < sy; iy++) {
                    for (int ix = 0; ix < sx; ix++) {
                        int idx = region.flatIndex(ix, iy, iz);
                        if (idx < 0) continue;
                        if (region.getType()[idx] != DiffusionRegion.TYPE_ACTIVE) continue;

                        BlockPos pos = new BlockPos(
                            region.getOriginX() + ix,
                            region.getOriginY() + iy,
                            region.getOriginZ() + iz);

                        float J = getCurrentDensityAt(pos);
                        float sigmaCtr = Math.max(sigma[idx], 1e-6f);
                        float P = J * J / sigmaCtr;  // Joule heat density (W/m³)

                        if (P > EmConstants.JOULE_HEAT_THRESHOLD) {
                            thermalInjector.inject(pos, P);
                        }
                    }
                }
            }
        }
    }

    /** Returns the thermal injector for PFSF integration (drain pending injections each tick). */
    public EmThermalInjector getThermalInjector() { return thermalInjector; }

    @Override
    public float getElectricPotentialAt(@Nonnull BlockPos pos) {
        DiffusionRegion r = registry.getRegion(pos, EmConstants.DEFAULT_REGION_SIZE);
        if (r != null) { int idx = r.flatIndex(pos); if (idx >= 0) return r.getPhi()[idx]; }
        return 0f;
    }

    @Override
    public float getCurrentDensityAt(@Nonnull BlockPos pos) {
        // J = σ × |∇φ|  via Zienkiewicz-Zhu superconvergent patch recovery
        DiffusionRegion region = registry.getRegion(pos, EmConstants.DEFAULT_REGION_SIZE);
        if (region == null) return 0f;

        int idx = region.flatIndex(pos);
        if (idx < 0) return 0f;

        float[] phi = region.getPhi();
        float[] sigma = region.getConductivity();

        // ZZ patch: recover gradient [a0, ax, ay, az] over 3×3×3 neighborhood
        float[] a = EmZZPatch.recoverGradient(pos, phi, region);

        // |∇φ| = sqrt(ax² + ay² + az²)
        float gradMag = (float) Math.sqrt(a[1] * a[1] + a[2] * a[2] + a[3] * a[3]);

        // J = σ_center × |∇φ|  (A/m²)
        float sigmaCtr = sigma[idx];
        return sigmaCtr * gradMag;
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
