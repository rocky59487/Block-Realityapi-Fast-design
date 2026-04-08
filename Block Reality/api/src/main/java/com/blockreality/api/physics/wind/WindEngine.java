package com.blockreality.api.physics.wind;

import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DiffusionRegionRegistry;
import com.blockreality.api.physics.solver.DiffusionSolver;
import com.blockreality.api.spi.IWindManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 風場引擎 — 混合架構：獨立 advection + 共用壓力投射。
 *
 * <p>Tick 流程（Chorin 分裂法）：
 * <ol>
 *   <li>WindAdvector.advect() — 對流步（Stam 1999 回溯法）</li>
 *   <li>WindAdvector.computeDivergence() — 計算散度 → source[]</li>
 *   <li>DiffusionSolver.rbgsSolve() — 壓力 Poisson 求解（共用！）</li>
 *   <li>WindAdvector.projectVelocity() — 速度修正 u = u* - ∇p</li>
 * </ol>
 */
public class WindEngine implements IWindManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-WindEngine");
    private static WindEngine instance;

    private boolean initialized = false;
    private final WindTranslator translator = new WindTranslator();
    private final DiffusionRegionRegistry registry = new DiffusionRegionRegistry("wind");

    // 速度場（每區域，與 DiffusionRegion 同大小）
    private final ConcurrentHashMap<Integer, float[]> velocityX = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, float[]> velocityY = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, float[]> velocityZ = new ConcurrentHashMap<>();

    private WindEngine() {}

    public static WindEngine getInstance() {
        if (instance == null) instance = new WindEngine();
        return instance;
    }

    @Override
    public void init(@Nonnull ServerLevel level) {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BR-Wind] Wind engine initialized");
    }

    @Override
    public void tick(@Nonnull ServerLevel level, int tickBudgetMs) {
        if (!initialized) return;
        long start = System.nanoTime();
        float dt = 0.05f * WindConstants.CFL_LIMIT;

        for (DiffusionRegion region : registry.getActiveRegions()) {
            if ((System.nanoTime() - start) / 1_000_000 >= tickBudgetMs) break;
            if (!region.isDirty()) continue;

            int id = region.getRegionId();
            int n = region.getTotalVoxels();
            float[] vx = velocityX.computeIfAbsent(id, k -> new float[n]);
            float[] vy = velocityY.computeIfAbsent(id, k -> new float[n]);
            float[] vz = velocityZ.computeIfAbsent(id, k -> new float[n]);

            // Step 1: Advection（唯一非共用步）
            WindAdvector.advect(vx, vy, vz, region, dt);

            // Step 2: Divergence → source[]
            WindAdvector.computeDivergence(vx, vy, vz, region);

            // Step 3: Pressure Poisson（共用 DiffusionSolver！）
            DiffusionSolver.rbgsSolve(region,
                translator.getDefaultMaxIterations(),
                translator.getDefaultDiffusionRate(),
                translator.getGravityWeight());

            // Step 4: Velocity correction
            WindAdvector.projectVelocity(vx, vy, vz, region);

            region.clearDirty();
        }
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        registry.clear();
        velocityX.clear();
        velocityY.clear();
        velocityZ.clear();
        initialized = false;
        LOGGER.info("[BR-Wind] Shutdown complete");
    }

    @Override
    public float getWindSpeedAt(@Nonnull BlockPos pos) {
        DiffusionRegion r = registry.getRegion(pos, WindConstants.DEFAULT_REGION_SIZE);
        if (r == null) return 0f;
        int idx = r.flatIndex(pos);
        if (idx < 0) return 0f;
        int id = r.getRegionId();
        float[] vx = velocityX.get(id), vy = velocityY.get(id), vz = velocityZ.get(id);
        if (vx == null) return 0f;
        return (float) Math.sqrt(vx[idx]*vx[idx] + vy[idx]*vy[idx] + vz[idx]*vz[idx]);
    }

    @Override
    public float getWindPressureAt(@Nonnull BlockPos pos) {
        return WindConstants.windPressure(getWindSpeedAt(pos));
    }

    @Override
    public void setWindSource(@Nonnull BlockPos pos, float speed, float dirX, float dirY, float dirZ) {
        DiffusionRegion r = registry.getOrCreateRegion(pos, WindConstants.DEFAULT_REGION_SIZE);
        int idx = r.flatIndex(pos);
        if (idx < 0) return;
        int id = r.getRegionId();
        int n = r.getTotalVoxels();
        float len = (float) Math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ);
        if (len < 1e-6f) len = 1f;
        velocityX.computeIfAbsent(id, k -> new float[n])[idx] = speed * dirX / len;
        velocityY.computeIfAbsent(id, k -> new float[n])[idx] = speed * dirY / len;
        velocityZ.computeIfAbsent(id, k -> new float[n])[idx] = speed * dirZ / len;
        r.getType()[idx] = DiffusionRegion.TYPE_ACTIVE;
        r.markDirty();
    }

    @Override
    public void removeWindSource(@Nonnull BlockPos pos) {
        DiffusionRegion r = registry.getRegion(pos, WindConstants.DEFAULT_REGION_SIZE);
        if (r == null) return;
        int idx = r.flatIndex(pos);
        if (idx < 0) return;
        int id = r.getRegionId();
        float[] vx = velocityX.get(id), vy = velocityY.get(id), vz = velocityZ.get(id);
        if (vx != null) { vx[idx] = 0; vy[idx] = 0; vz[idx] = 0; }
        r.markDirty();
    }

    @Override
    public int getActiveRegionCount() { return registry.getRegionCount(); }
}
