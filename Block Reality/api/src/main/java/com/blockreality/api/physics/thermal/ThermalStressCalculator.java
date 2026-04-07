package com.blockreality.api.physics.thermal;

import com.blockreality.api.material.ThermalProfile;
import com.blockreality.api.physics.solver.DiffusionRegion;
import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * 熱應力計算器 — 從溫度場計算結構熱應力，注入 PFSF source term。
 *
 * <p>公式：σ_th = α_expansion × E × ΔT
 *
 * <p>當 ΔT > {@link ThermalConstants#MIN_COUPLING_DELTA_T} 時，
 * 熱應力轉換為 PFSF source term，可能觸發：
 * <ul>
 *   <li>{@code THERMAL_STRESS} — 熱脹冷縮超過材料屈服強度</li>
 *   <li>{@code THERMAL_SPALLING} — 表面溫度梯度導致混凝土剝落</li>
 * </ul>
 */
public class ThermalStressCalculator {

    /**
     * 從已求解的溫度場提取需要耦合到 PFSF 的熱應力。
     *
     * @param region 已求解的擴散區域（phi[] = 溫度 °C）
     * @return 方塊位置 → 等效 PFSF source term (Pa) 的映射
     */
    @Nonnull
    public static Map<BlockPos, Float> extractThermalStresses(@Nonnull DiffusionRegion region) {
        Map<BlockPos, Float> stresses = new HashMap<>();

        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] phi = region.getPhi();
        byte[] type = region.getType();

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (type[idx] != DiffusionRegion.TYPE_ACTIVE) continue;

                    float temperature = phi[idx];
                    float deltaT = temperature - ThermalConstants.AMBIENT_TEMPERATURE;

                    if (Math.abs(deltaT) < ThermalConstants.MIN_COUPLING_DELTA_T) continue;

                    // 預設使用混凝土的熱膨脹係數和楊氏模量
                    ThermalProfile profile = ThermalProfile.CONCRETE;
                    float thermalStress = (float) profile.thermalStress(
                        30e9,  // E = 30 GPa (混凝土)
                        deltaT
                    );

                    if (Math.abs(thermalStress) > 1000f) {  // > 1 kPa 才注入
                        BlockPos pos = new BlockPos(
                            x + region.getOriginX(),
                            y + region.getOriginY(),
                            z + region.getOriginZ()
                        );
                        stresses.put(pos, thermalStress);
                    }
                }
            }
        }
        return stresses;
    }
}
