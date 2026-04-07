package com.blockreality.api.physics.thermal;

import com.blockreality.api.material.ThermalProfile;
import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DomainTranslator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;

/**
 * 熱傳導轉譯層 — 將溫度/熱源映射到通用擴散求解器。
 *
 * <h3>映射表</h3>
 * <pre>
 * 域概念           → 求解器概念
 * ──────────────────────────────
 * 溫度 T (°C)      → φ (phi)
 * 熱擴散率 α=k/(ρc) → σ (conductivity)
 * 熱源 Q/(ρc)      → f (source)
 * 固體/空氣         → type[]
 * gravityWeight     → 0.0（無重力驅動）
 * </pre>
 */
public class ThermalTranslator implements DomainTranslator {

    /** 預設密度（用於無材料資訊的方塊） */
    private static final float DEFAULT_DENSITY = 2400f;

    @Override
    public void populateRegion(@Nonnull DiffusionRegion region, @Nonnull ServerLevel level) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    BlockPos worldPos = new BlockPos(
                        x + region.getOriginX(),
                        y + region.getOriginY(),
                        z + region.getOriginZ()
                    );
                    BlockState state = level.getBlockState(worldPos);

                    if (state.isAir()) {
                        // 空氣：低導熱率的活動體素（熱空氣可擴散）
                        ThermalProfile air = ThermalProfile.AIR;
                        float alpha = (float) air.diffusivity(1.225); // 空氣密度
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE, alpha,
                            ThermalConstants.AMBIENT_TEMPERATURE, 0f);
                    } else if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
                        // 火焰：高溫熱源
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE, 0.5f,
                            ThermalConstants.FIRE_TEMPERATURE,
                            ThermalConstants.FIRE_TEMPERATURE * 0.01f); // 持續熱源
                    } else if (state.is(Blocks.LAVA)) {
                        // 熔岩：極高溫熱源
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE, 0.5f,
                            ThermalConstants.LAVA_TEMPERATURE,
                            ThermalConstants.LAVA_TEMPERATURE * 0.01f);
                    } else {
                        // 固體方塊：從材料系統取得熱學屬性
                        ThermalProfile profile = ThermalProfile.CONCRETE; // 預設混凝土
                        float alpha = (float) profile.diffusivity(DEFAULT_DENSITY);
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE, alpha,
                            ThermalConstants.AMBIENT_TEMPERATURE, 0f);
                    }
                }
            }
        }
    }

    @Override
    public void interpretResults(@Nonnull DiffusionRegion region) {
        // phi[] 現在包含溫度場（°C）
        // 後續由 ThermalStructureCoupler 讀取溫度並計算熱應力
    }

    @Override
    public float getGravityWeight() { return 0.0f; }

    @Override
    public String getDomainId() { return "thermal"; }

    @Override
    public float getDefaultDiffusionRate() { return ThermalConstants.DIFFUSION_RATE; }

    @Override
    public int getDefaultMaxIterations() { return ThermalConstants.DEFAULT_ITERATIONS_PER_TICK; }
}
