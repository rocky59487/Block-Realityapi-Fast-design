package com.blockreality.api.physics.fluid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSF-Fluid CPU solver unit test.
 *
 * verify:
 * - Mass conservation (Σvolume remains constant after iterations)
 * - Steady-state convergence (potential energy gradient → 0)
 * - Boundaries do not leak (Neumann BC correct)
 * - Solid wall isolation
 * - Pressure coupling correctness
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
        // Full air area, there should be no changes after iteration
        float maxDelta = FluidCPUSolver.jacobiStep(region, DIFFUSION_RATE);
        assertEquals(0f, maxDelta, EPSILON, "Empty region should have zero delta");
    }

    @Test
    void testSingleWaterBlock_staysInPlace() {
        // Place a grid of water in the center and air around it
        int cx = SMALL_SIZE / 2, cy = SMALL_SIZE / 2, cz = SMALL_SIZE / 2;
        int idx = region.flatIndex(cx, cy, cz);
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        float initPhi = density * g * cy;
        region.setFluidState(idx, FluidType.WATER, 1.0f, initPhi, initPhi);

        int iters = FluidCPUSolver.solve(region, 50, DIFFUSION_RATE);
        assertTrue(iters > 0, "Should perform at least 1 iteration");

        // The potential energy should gradually decrease (water diffuses towards neighbors)
        float finalPhi = region.getPhi()[idx];
        assertTrue(finalPhi <= initPhi + EPSILON,
            "Water potential should decrease or stay as it diffuses");
    }

    @Test
    void testMassConservation_closedBox() {
        // Inject water into a closed space (surrounded by solid walls) to verify mass conservation
        // The outer layer is a solid wall and the inner layer is water
        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = 0; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == 0 || x == SMALL_SIZE - 1 ||
                        y == 0 || y == SMALL_SIZE - 1 ||
                        z == 0 || z == SMALL_SIZE - 1) {
                        // solid wall
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    } else if (y <= 3) {
                        // Lower half filled with water
                        float density = (float) FluidType.WATER.getDensity();
                        float g = (float) FluidConstants.GRAVITY;
                        float phi = density * g * y;
                        region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                    } else {
                        // upper air
                        region.setFluidState(idx, FluidType.WATER, 0.01f, 0f, 0f);
                    }
                }
            }
        }

        double volumeBefore = FluidCPUSolver.computeTotalVolume(region);
        assertTrue(volumeBefore > 0, "Should have initial fluid volume");

        // Perform multiple iterations
        FluidCPUSolver.solve(region, 100, DIFFUSION_RATE);

        double volumeAfter = FluidCPUSolver.computeTotalVolume(region);

        // Conservation of mass: the total volume change should be within a reasonable range
        // Note: The Jacobi method is subject to slight deviation due to numerical diffusion, but should not exceed 10%
        double relativeChange = Math.abs(volumeAfter - volumeBefore) / volumeBefore;
        assertTrue(relativeChange < 0.1,
            String.format("Mass should be approximately conserved. Before=%.4f, After=%.4f, Change=%.2f%%",
                volumeBefore, volumeAfter, relativeChange * 100));
    }

    @Test
    void testSteadyStateConvergence() {
        // A uniform water body should quickly converge to a steady state
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;

        for (int z = 1; z < SMALL_SIZE - 1; z++) {
            for (int y = 1; y < SMALL_SIZE - 1; y++) {
                for (int x = 1; x < SMALL_SIZE - 1; x++) {
                    int idx = region.flatIndex(x, y, z);
                    // phi = ρg*(maxY - y), so that H = phi + ρgy = ρg*maxY = constant (hydrostatic equilibrium form).
                    // H is the same for all fluid neighbors, Jacobi residuals are 0, and should converge at step 1.
                    float phi = density * g * (SMALL_SIZE - 1 - y);
                    region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                }
            }
        }
        // outer solid wall
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

        // A homogeneous body of water (already in hydrostatic equilibrium) should converge quickly
        assertTrue(iters < 200,
            "Uniform water body should converge before max iterations, but took " + iters);
    }

    @Test
    void testSolidWallBlocks_fluidFlow() {
        // Solid walls should resist the passage of fluids
        // Water on the left half, wall in the middle, empty space on the right half
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;

        for (int z = 0; z < SMALL_SIZE; z++) {
            for (int y = 0; y < SMALL_SIZE; y++) {
                for (int x = 0; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == SMALL_SIZE / 2) {
                        // middle wall
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    } else if (x < SMALL_SIZE / 2) {
                        // left half water
                        float phi = density * g * y;
                        region.setFluidState(idx, FluidType.WATER, 1.0f, phi, phi);
                    }
                    // Keep air on the right side
                }
            }
        }

        FluidCPUSolver.solve(region, 50, DIFFUSION_RATE);

        // There should be no water on the right half (isolated by a solid wall)
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
    void testRBGSConvergenceFasterThanJacobi() {
        FluidRegion regionJ = new FluidRegion(1, 0, 0, 0, 16, 16, 16);
        FluidRegion regionR = new FluidRegion(2, 0, 0, 0, 16, 16, 16);
        // Inject a fluid source in the center
        int cx = 8, cy = 8, cz = 8;
        int idxJ = regionJ.flatIndex(cx, cy, cz);
        int idxR = regionR.flatIndex(cx, cy, cz);
        regionJ.setFluidState(idxJ, FluidType.WATER, 1.0f, 1000f, 1000f);
        regionR.setFluidState(idxR, FluidType.WATER, 1.0f, 1000f, 1000f);

        int itersJ = 0;
        float maxDeltaJ;
        do {
            maxDeltaJ = FluidCPUSolver.jacobiStep(regionJ, 0.25f);
            itersJ++;
        } while (maxDeltaJ > EPSILON && itersJ < 500);

        int itersR = 0;
        float maxDeltaR;
        do {
            maxDeltaR = FluidCPUSolver.rbgsStep(regionR, 0.25f);
            itersR++;
        } while (maxDeltaR > EPSILON && itersR < 500);

        assertTrue(itersR <= itersJ, "RBGS should converge faster or equal to Jacobi");
    }

    @Test
    void testBoundaryPressure_extraction() {
        // There is a solid next to the water → the boundary pressure should be extracted
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        int waterIdx = region.flatIndex(3, 3, 3);
        float pressure = density * g * 3;
        region.setFluidState(waterIdx, FluidType.WATER, 1.0f, pressure, pressure);

        // Place solid wall next to it
        int wallIdx = region.flatIndex(4, 3, 3);
        region.setFluidState(wallIdx, FluidType.SOLID_WALL, 0f, 0f, 0f);

        var pressureMap = FluidPressureCoupler.extractBoundaryPressures(region);

        // The pressure that should be extracted to the solid wall
        if (pressure >= FluidConstants.MIN_COUPLING_PRESSURE) {
            assertFalse(pressureMap.isEmpty(), "Should extract boundary pressure from wall adjacent to water");
        }
    }

    @Test
    void testFluidType_properties() {
        // Verify FluidType basic properties
        assertEquals(0, FluidType.AIR.getId());
        assertEquals(1, FluidType.WATER.getId());
        assertEquals(1000.0, FluidType.WATER.getDensity(), 0.01);
        assertTrue(FluidType.WATER.isFlowable());
        assertFalse(FluidType.AIR.isFlowable());
        assertFalse(FluidType.SOLID_WALL.isFlowable());

        // fromId reverse check
        assertEquals(FluidType.WATER, FluidType.fromId(1));
        assertEquals(FluidType.AIR, FluidType.fromId(99)); // Unknown value→AIR
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

        // Coordinates within the area
        assertTrue(r.contains(new net.minecraft.core.BlockPos(105, 70, 210)));
        // Coordinates outside the area
        assertFalse(r.contains(new net.minecraft.core.BlockPos(99, 70, 210)));
        assertFalse(r.contains(new net.minecraft.core.BlockPos(116, 70, 210)));

        // flatIndex correctness
        int idx = r.flatIndex(new net.minecraft.core.BlockPos(100, 64, 200));
        assertEquals(0, idx, "Origin should map to index 0");

        int idx2 = r.flatIndex(new net.minecraft.core.BlockPos(101, 64, 200));
        assertEquals(1, idx2, "X+1 should map to index 1");
    }

    @Test
    void testNaNProtection() {
        // Make sure the solver does not produce NaN/Inf
        int idx = region.flatIndex(4, 4, 4);
        region.setFluidState(idx, FluidType.WATER, 1.0f, Float.MAX_VALUE, Float.MAX_VALUE);

        FluidCPUSolver.solve(region, 10, DIFFUSION_RATE);

        float phi = region.getPhi()[idx];
        assertFalse(Float.isNaN(phi), "Phi should not be NaN");
        assertFalse(Float.isInfinite(phi), "Phi should not be Infinite");
    }

    // ═══ Audit fix: Residual monotonicity + RBGS mass conservation + pressure propagation ═══

    @Test
    void testRBGS_massConservation() {
        setupClosedWaterBox();
        double volumeBefore = FluidCPUSolver.computeTotalVolume(region);
        FluidCPUSolver.rbgsSolve(region, 50, DIFFUSION_RATE);
        double volumeAfter = FluidCPUSolver.computeTotalVolume(region);
        double relativeChange = Math.abs(volumeAfter - volumeBefore) / volumeBefore;
        assertTrue(relativeChange < 0.1,
            String.format("RBGS mass conservation: Before=%.4f, After=%.4f, Change=%.2f%%",
                volumeBefore, volumeAfter, relativeChange * 100));
    }

    @Test
    void testResidualDecreasesMonotonically() {
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        // Non-equilibrium water body (phi = ρgy, non-hydrostatic equilibrium)
        for (int z = 1; z < SMALL_SIZE - 1; z++)
            for (int y = 1; y < SMALL_SIZE - 1; y++)
                for (int x = 1; x < SMALL_SIZE - 1; x++)
                    region.setFluidState(region.flatIndex(x, y, z),
                        FluidType.WATER, 1.0f, density * g * y, density * g * y);
        for (int z = 0; z < SMALL_SIZE; z++)
            for (int y = 0; y < SMALL_SIZE; y++)
                for (int x = 0; x < SMALL_SIZE; x++)
                    if (x == 0 || x == SMALL_SIZE-1 || y == 0 || y == SMALL_SIZE-1 || z == 0 || z == SMALL_SIZE-1)
                        region.setFluidState(region.flatIndex(x, y, z), FluidType.SOLID_WALL, 0f, 0f, 0f);

        float prevRes = FluidCPUSolver.computeMaxResidual(region);
        int violations = 0;
        for (int i = 0; i < 20; i++) {
            FluidCPUSolver.jacobiStep(region, DIFFUSION_RATE);
            float res = FluidCPUSolver.computeMaxResidual(region);
            if (res > prevRes * 1.01f) violations++;
            prevRes = res;
        }
        assertTrue(violations <= 2, "Residual should generally decrease, got " + violations + " violations");
    }

    @Test
    void testPressurePropagation_waterColumn() {
        setupClosedWaterBox();
        FluidCPUSolver.solve(region, 100, DIFFUSION_RATE);
        var pressureMap = FluidPressureCoupler.extractBoundaryPressures(region);
        boolean hasBottomPressure = pressureMap.entrySet().stream()
            .anyMatch(e -> e.getKey().getY() == 0 && e.getValue() > 0);
        assertTrue(hasBottomPressure, "Bottom wall should have positive boundary pressure from water column");
    }

    private void setupClosedWaterBox() {
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        for (int z = 0; z < SMALL_SIZE; z++)
            for (int y = 0; y < SMALL_SIZE; y++)
                for (int x = 0; x < SMALL_SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == 0 || x == SMALL_SIZE-1 || y == 0 || y == SMALL_SIZE-1 || z == 0 || z == SMALL_SIZE-1)
                        region.setFluidState(idx, FluidType.SOLID_WALL, 0f, 0f, 0f);
                    else if (y <= 3)
                        region.setFluidState(idx, FluidType.WATER, 1.0f, density * g * y, density * g * y);
                    else
                        region.setFluidState(idx, FluidType.WATER, 0.01f, 0f, 0f);
                }
    }
}
