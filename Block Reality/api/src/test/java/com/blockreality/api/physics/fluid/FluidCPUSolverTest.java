package com.blockreality.api.physics.fluid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSF-Fluid CPU 求解器單元測試。
 *
 * 驗證：
 * - 質量守恆（Σvolume 在迭代後保持恆定）
 * - 穩態收斂（勢能梯度→0）
 * - 邊界不洩漏（Neumann BC 正確）
 * - 固體牆面隔離
 * - 壓力耦合正確性
 */
class FluidCPUSolverTest {

    private static final float EPSILON = 1e-3f;
    private static final int SMALL_SIZE = 8;
    private static final float DIFFUSION_RATE = 0.25f;

    private FluidRegion region;

    @BeforeEach
    void setUp() {
        region = new FluidRegion(1, 0, 0, 0, SMALL_SIZE, SMALL_SIZE, SMALL_SIZE);
    }

    @Test
    void testEmptyRegion_noChange() {
        // 全空氣區域，迭代後不應有任何變化
        float maxDelta = FluidCPUSolver.jacobiStep(region, DIFFUSION_RATE);
        assertEquals(0f, maxDelta, EPSILON, "Empty region should have zero delta");
    }

    @Test
    void testSingleWaterBlock_staysInPlace() {
        // 中心放一格水，周圍為空氣
        int cx = SMALL_SIZE / 2, cy = SMALL_SIZE / 2, cz = SMALL_SIZE / 2;
        int idx = region.flatIndex(cx, cy, cz);
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        float initPhi = density * g * cy;
        region.setFluidState(idx, FluidType.WATER, 1.0f, initPhi, initPhi);

        int iters = FluidCPUSolver.solve(region, 50, DIFFUSION_RATE);
        assertTrue(iters > 0, "Should perform at least 1 iteration");

        // 勢能應該逐漸降低（水向鄰居擴散）
        float finalPhi = region.getPhi()[idx];
        assertTrue(finalPhi <= initPhi + EPSILON,
            "Water potential should decrease or stay as it diffuses");
    }

    @Test
    void testMassConservation_closedBox() {
        // 在封閉空間內（四周固體牆）注入水，驗證質量守恆
        // 外層為固體牆，內層為水
        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = 0; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == 0 || x == SMALL_SIZE - 1 ||
                        y == 0 || y == SMALL_SIZE - 1 ||
                        z == 0 || z == SMALL_SIZE - 1) {
                        // 固體牆
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    } else if (y <= 3) {
                        // 下半部充水
                        float density = (float) FluidType.WATER.getDensity();
                        float g = (float) FluidConstants.GRAVITY;
                        float phi = density * g * y;
                        region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                    } else {
                        // 上半部空氣
                        region.setFluidState(idx, FluidType.WATER, 0.01f, 0f, 0f);
                    }
                }
            }
        }

        double volumeBefore = FluidCPUSolver.computeTotalVolume(region);
        assertTrue(volumeBefore > 0, "Should have initial fluid volume");

        // 執行多次迭代
        FluidCPUSolver.solve(region, 100, DIFFUSION_RATE);

        double volumeAfter = FluidCPUSolver.computeTotalVolume(region);

        // 質量守恆：總體積變化應在合理範圍內
        // 注意：Jacobi 方法會因數值擴散有微小偏差，但不應超過 10%
        double relativeChange = Math.abs(volumeAfter - volumeBefore) / volumeBefore;
        assertTrue(relativeChange < 0.1,
            String.format("Mass should be approximately conserved. Before=%.4f, After=%.4f, Change=%.2f%%",
                volumeBefore, volumeAfter, relativeChange * 100));
    }

    @Test
    void testSteadyStateConvergence() {
        // 均勻水體應快速收斂到穩態
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;

        for (int z = 1; z < SMALL_SIZE - 1; z++) {
            for (int y = 1; y < SMALL_SIZE - 1; y++) {
                for (int x = 1; x < SMALL_SIZE - 1; x++) {
                    int idx = region.flatIndex(x, y, z);
                    // phi = ρg*(maxY - y)，使 H = phi + ρgy = ρg*maxY = 常數（靜水壓平衡形式）。
                    // 所有流體鄰居的 H 相同，Jacobi 殘差為 0，應在第 1 步收斂。
                    float phi = density * g * (SMALL_SIZE - 1 - y);
                    region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                }
            }
        }
        // 外層固體牆
        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = 0; x < SMALL_SIZE; x++) {
                    if (x == 0 || x == SMALL_SIZE - 1 ||
                        y == 0 || y == SMALL_SIZE - 1 ||
                        z == 0 || z == SMALL_SIZE - 1) {
                        int idx = region.flatIndex(x, y, z);
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    }
                }
            }
        }

        int iters = FluidCPUSolver.solve(region, 200, DIFFUSION_RATE);

        // 均勻水體（已處於靜水壓平衡）應該很快收斂
        assertTrue(iters < 200,
            "Uniform water body should converge before max iterations, but took " + iters);
    }

    @Test
    void testSolidWallBlocks_fluidFlow() {
        // 固體牆面應阻擋流體通過
        // 左半邊水，中間牆，右半邊空
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;

        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = 0; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == SMALL_SIZE / 2) {
                        // 中間牆
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    } else if (x < SMALL_SIZE / 2) {
                        // 左半邊水
                        float phi = density * g * y;
                        region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                    }
                    // 右半邊保持空氣
                }
            }
        }

        FluidCPUSolver.solve(region, 50, DIFFUSION_RATE);

        // 右半邊不應有水（被固體牆隔離）
        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = SMALL_SIZE / 2 + 1; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    FluidState state = region.getFluidState(idx);
                    assertEquals(0f, state.volume(), EPSILON,
                        String.format("Right side at (%d,%d,%d) should have no water", x, y, z));
                }
            }
        }
    }

    @Test
    void testBoundaryPressure_extraction() {
        // 水旁邊有固體 → 應提取邊界壓力
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        int waterIdx = region.flatIndex(3, 3, 3);
        float pressure = density * g * 3;
        region.setFluidState(waterIdx, FluidType.WATER, 1.0f, pressure, pressure);

        // 旁邊放固體牆
        int wallIdx = region.flatIndex(4, 3, 3);
        region.setFluidState(wallIdx, FluidType.SOLID_WALL, 0f, 0f, 0f);

        var pressureMap = FluidPressureCoupler.extractBoundaryPressures(region);

        // 應該提取到固體牆面的壓力
        if (pressure >= FluidConstants.MIN_COUPLING_PRESSURE) {
            assertFalse(pressureMap.isEmpty(), "Should extract boundary pressure from wall adjacent to water");
        }
    }

    @Test
    void testFluidType_properties() {
        // 驗證 FluidType 基本屬性
        assertEquals(0, FluidType.AIR.getId());
        assertEquals(1, FluidType.WATER.getId());
        assertEquals(1000.0, FluidType.WATER.getDensity(), 0.01);
        assertTrue(FluidType.WATER.isFlowable());
        assertFalse(FluidType.AIR.isFlowable());
        assertFalse(FluidType.SOLID_WALL.isFlowable());

        // fromId 反查
        assertEquals(FluidType.WATER, FluidType.fromId(1));
        assertEquals(FluidType.AIR, FluidType.fromId(99)); // 未知值→AIR
    }

    @Test
    void testFluidState_record() {
        FluidState empty = FluidState.EMPTY;
        assertFalse(empty.hasFluid());
        assertFalse(empty.isSolid());

        FluidState solid = FluidState.SOLID;
        assertTrue(solid.isSolid());
        assertFalse(solid.hasFluid());

        FluidState water = FluidState.fullWater(10f);
        assertTrue(water.hasFluid());
        assertFalse(water.isSolid());
        assertEquals(1.0f, water.volume(), EPSILON);
        assertTrue(water.pressure() > 0);
    }

    @Test
    void testFluidRegion_coordinateMapping() {
        FluidRegion r = new FluidRegion(1, 100, 64, 200, 16, 16, 16);

        // 區域內座標
        assertTrue(r.contains(new net.minecraft.core.BlockPos(105, 70, 210)));
        // 區域外座標
        assertFalse(r.contains(new net.minecraft.core.BlockPos(99, 70, 210)));
        assertFalse(r.contains(new net.minecraft.core.BlockPos(116, 70, 210)));

        // flatIndex 正確性
        int idx = r.flatIndex(new net.minecraft.core.BlockPos(100, 64, 200));
        assertEquals(0, idx, "Origin should map to index 0");

        int idx2 = r.flatIndex(new net.minecraft.core.BlockPos(101, 64, 200));
        assertEquals(1, idx2, "X+1 should map to index 1");
    }

    @Test
    void testNaNProtection() {
        // 確保 solver 不會產生 NaN/Inf
        int idx = region.flatIndex(4, 4, 4);
        region.setFluidState(idx, FluidType.WATER, 1.0f, Float.MAX_VALUE, Float.MAX_VALUE);

        FluidCPUSolver.solve(region, 10, DIFFUSION_RATE);

        float phi = region.getPhi()[idx];
        assertFalse(Float.isNaN(phi), "Phi should not be NaN");
        assertFalse(Float.isInfinite(phi), "Phi should not be Infinite");
    }
}
