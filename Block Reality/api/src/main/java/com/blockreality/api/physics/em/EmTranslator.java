package com.blockreality.api.physics.em;

import com.blockreality.api.material.ElectricalProfile;
import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.solver.DomainTranslator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nonnull;

/**
 * 電磁場轉譯層 — 將電位/電導率映射到通用擴散求解器。
 *
 * <h3>映射</h3>
 * <pre>
 * 電位 V         → φ
 * 電導率 σ_elec  → conductivity[]
 * -ρ_charge/ε    → source[]
 * gravityWeight  → 0.0
 * </pre>
 */
public class EmTranslator implements DomainTranslator {

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

                    var state = level.getBlockState(worldPos);
                    if (state.isAir()) {
                        // 空氣：極低電導率（絕緣體）
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE,
                            (float) ElectricalProfile.AIR.conductivity(), 0f, 0f);
                    } else if (state.is(Blocks.LIGHTNING_ROD)) {
                        // 避雷針：高電導率 + 接地（Dirichlet BC via low phi）
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE,
                            (float) ElectricalProfile.COPPER.conductivity(), 0f, 0f);
                    } else {
                        // 固體：根據材料電導率
                        ElectricalProfile profile = ElectricalProfile.STONE;
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE,
                            (float) profile.conductivity(), 0f, 0f);
                    }
                }
            }
        }
    }

    @Override
    public void interpretResults(@Nonnull DiffusionRegion region) {
        // phi[] = 電位 V。後續由 LightningPathfinder 讀取梯度。
    }

    @Override public float getGravityWeight() { return 0.0f; }
    @Override public String getDomainId() { return "em"; }
    @Override public float getDefaultDiffusionRate() { return EmConstants.DIFFUSION_RATE; }
    @Override public int getDefaultMaxIterations() { return EmConstants.DEFAULT_ITERATIONS_PER_TICK; }
}
