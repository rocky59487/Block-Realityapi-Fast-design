package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * 流體壓力耦合器 — 將流體邊界壓力轉換為 PFSF 結構引擎的 source term。
 *
 * <p>掃描所有流體區域的固體邊界，找出流體與結構接觸的面，
 * 將靜水壓轉換為作用力並提供給 {@code PFSFDataBuilder} 注入 source term。
 *
 * <h3>耦合方程</h3>
 * <pre>
 * 對每個固體牆面 i，若相鄰流體體素 j 的壓力 P_j：
 *   F_i = Σ_j (P_j × BLOCK_FACE_AREA × PRESSURE_COUPLING_FACTOR)
 * 此力作為 PFSF 的額外 source term 注入。
 * </pre>
 *
 * <p>耦合為單向（流體→結構），結構對流體的影響透過
 * {@code FluidBarrierBreachEvent} 事件處理。
 */
public class FluidPressureCoupler {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidCoupler");

    // 6 鄰居偏移
    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    /**
     * 掃描指定流體區域，提取所有固體邊界處的壓力。
     *
     * <p>返回一個 Map：固體方塊位置 → 受到的流體壓力 (Pa)。
     * 此 Map 由 PFSF 引擎在下一 tick 的 source term 計算中使用。
     *
     * @param region 流體區域
     * @return 固體邊界壓力 map（僅包含壓力 > MIN_COUPLING_PRESSURE 的項）
     */
    @Nonnull
    public static Map<BlockPos, Float> extractBoundaryPressures(@Nonnull FluidRegion region) {
        Map<BlockPos, Float> pressureMap = new HashMap<>();
        int sx = region.getSizeX();
        int sy = region.getSizeY();
        int sz = region.getSizeZ();
        byte[] type = region.getType();
        float[] pressure = region.getPressure();
        float[] vol = region.getVolume();

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    FluidType ft = FluidType.fromId(type[idx] & 0xFF);

                    // 只看流體體素
                    if (!ft.isFlowable()) continue;
                    if (vol[idx] < FluidConstants.MIN_VOLUME_FRACTION) continue;

                    float fluidPressure = pressure[idx];
                    if (fluidPressure < FluidConstants.MIN_COUPLING_PRESSURE) continue;

                    // 檢查六鄰居是否為固體牆面
                    for (int[] offset : NEIGHBOR_OFFSETS) {
                        int nx = x + offset[0];
                        int ny = y + offset[1];
                        int nz = z + offset[2];

                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            continue;
                        }

                        int nIdx = region.flatIndex(nx, ny, nz);
                        FluidType nft = FluidType.fromId(type[nIdx] & 0xFF);

                        if (nft == FluidType.SOLID_WALL) {
                            BlockPos wallPos = new BlockPos(
                                nx + region.getOriginX(),
                                ny + region.getOriginY(),
                                nz + region.getOriginZ()
                            );

                            // 累加來自多個流體鄰居的壓力
                            float coupledForce = fluidPressure
                                * (float) FluidConstants.BLOCK_FACE_AREA
                                * FluidConstants.PRESSURE_COUPLING_FACTOR;

                            pressureMap.merge(wallPos, coupledForce, Float::sum);
                        }
                    }
                }
            }
        }

        return pressureMap;
    }

    /**
     * 從所有活動區域提取邊界壓力並合併。
     *
     * <p>此方法在 ServerTick 中呼叫，將結果傳遞給
     * {@code PFSFEngine.setFluidPressureLookup()}。
     *
     * @param registry 流體區域註冊表
     * @return 合併後的固體邊界壓力 map
     */
    @Nonnull
    public static Map<BlockPos, Float> extractAllBoundaryPressures(@Nonnull FluidRegionRegistry registry) {
        Map<BlockPos, Float> combined = new HashMap<>();
        for (FluidRegion region : registry.getActiveRegions()) {
            Map<BlockPos, Float> regionPressures = extractBoundaryPressures(region);
            for (Map.Entry<BlockPos, Float> entry : regionPressures.entrySet()) {
                combined.merge(entry.getKey(), entry.getValue(), Float::sum);
            }
        }
        return combined;
    }
}
