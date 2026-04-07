package com.blockreality.api.physics.wind;

import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DomainTranslator;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;

/**
 * 風場轉譯層 — 壓力投射步的通用求解器映射。
 *
 * <p>Wind 使用分步法：
 * <ol>
 *   <li>Advection pass（{@link WindAdvector}）：u* = u - dt(u·∇)u</li>
 *   <li>Pressure projection（此轉譯器 + DiffusionSolver）：∇²p = ∇·u*</li>
 *   <li>Velocity correction：u = u* - ∇p</li>
 * </ol>
 *
 * <p>此轉譯器只負責步驟 2（壓力 Poisson 求解）。
 */
public class WindTranslator implements DomainTranslator {

    @Override
    public void populateRegion(@Nonnull DiffusionRegion region, @Nonnull ServerLevel level) {
        // conductivity[] = 1/ρ_air（均勻）
        // source[] = divergence of u*（由 WindAdvector 預先計算）
        // type[] = ACTIVE for air, SOLID_WALL for blocks
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        for (int z = 0; z < sz; z++)
            for (int y = 0; y < sy; y++)
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    var worldPos = new net.minecraft.core.BlockPos(
                        x + region.getOriginX(), y + region.getOriginY(), z + region.getOriginZ());
                    var state = level.getBlockState(worldPos);

                    if (state.isAir()) {
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE,
                            1.0f / WindConstants.AIR_DENSITY, 0f, 0f);
                    } else {
                        region.setVoxel(idx, DiffusionRegion.TYPE_SOLID_WALL, 0f, 0f, 0f);
                    }
                }
    }

    @Override
    public void interpretResults(@Nonnull DiffusionRegion region) {
        // phi[] = 壓力場 p。後續由 WindEngine 計算 u = u* - ∇p。
    }

    @Override public float getGravityWeight() { return 0.0f; }
    @Override public String getDomainId() { return "wind"; }
    @Override public float getDefaultDiffusionRate() { return WindConstants.PRESSURE_DIFFUSION_RATE; }
    @Override public int getDefaultMaxIterations() { return WindConstants.DEFAULT_ITERATIONS_PER_TICK; }
}
