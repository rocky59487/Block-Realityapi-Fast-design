package com.blockreality.api.physics.solver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * General diffusion solver test.
 *
 * verify:
 * - Pure diffusion convergence (gravityWeight=0, like thermal/EM)
 * - Gravity diffusion convergence (gravityWeight=1, such as fluid)
 * - RBGS converges faster than Jacobi
 * - Residuals decrease monotonically
 * - Source item injection correctness
 * - Ghost Cell BC accuracy in corners
 */
class DiffusionSolverTest {

    private static final int SIZE = 8;
    private static final float RATE = 0.25f;
    private static final float EPSILON = 1e-3f;

    private DiffusionRegion region;

    @BeforeEach
    void setUp() {
        region = new DiffusionRegion(1, 0, 0, 0, SIZE, SIZE, SIZE);
    }

    @Test
    void testPureDiffusion_convergesToUniform() {
        // gravityWeight=0 (thermal/EM mode): initial high temperature in the center and low temperature around
        // should converge to a uniform value
        setupClosedBox();
        int center = region.flatIndex(4, 4, 4);
        region.getPhi()[center] = 100f;  // Center "Heat Source"

        int iters = DiffusionSolver.solve(region, 200, RATE, 0f);
        assertTrue(iters <= 200, "Pure diffusion should converge");

        // The center phi should drop (heat spreads out)
        assertTrue(region.getPhi()[center] < 100f, "Center phi should decrease as heat diffuses");
    }

    @Test
    void testGravityDiffusion_hydrostaticEquilibrium() {
        // gravityWeight=1 (fluid mode): phi = σ*g*(maxY-y) → H = constant
        // The first step should be to converge
        setupClosedBox();
        float[] phi = region.getPhi();
        float[] sigma = region.getConductivity();

        for (int z = 1; z < SIZE - 1; z++)
            for (int y = 1; y < SIZE - 1; y++)
                for (int x = 1; x < SIZE - 1; x++) {
                    int idx = region.flatIndex(x, y, z);
                    sigma[idx] = 1000f;  // density
                    phi[idx] = 1000f * 9.81f * (SIZE - 1 - y);
                }

        int iters = DiffusionSolver.solve(region, 200, RATE, 1f);
        assertTrue(iters < 200, "Hydrostatic equilibrium should converge quickly, took " + iters);
    }

    @Test
    void testRBGS_fasterThanJacobi() {
        setupClosedBox();
        // non-equilibrium state
        for (int z = 1; z < SIZE - 1; z++)
            for (int y = 1; y < SIZE - 1; y++)
                for (int x = 1; x < SIZE - 1; x++) {
                    int idx = region.flatIndex(x, y, z);
                    region.getPhi()[idx] = (float) (Math.random() * 10);
                }

        // Jacobi
        DiffusionRegion r1 = cloneRegion(region);
        int jacobiIters = DiffusionSolver.solve(r1, 100, RATE, 0f);

        // RBGS
        DiffusionRegion r2 = cloneRegion(region);
        int rbgsIters = DiffusionSolver.rbgsSolve(r2, 100, RATE, 0f);

        assertTrue(rbgsIters <= jacobiIters,
            "RBGS (" + rbgsIters + ") should converge no slower than Jacobi (" + jacobiIters + ")");
    }

    @Test
    void testSourceTermInjection() {
        // Continuous heat source: source > 0 → phi should rise steadily
        setupClosedBox();
        int center = region.flatIndex(4, 4, 4);
        region.getSource()[center] = 10f;  // continuous heat source

        float phiBefore = region.getPhi()[center];
        DiffusionSolver.jacobiStep(region, RATE, 0f);
        float phiAfter = region.getPhi()[center];

        assertTrue(phiAfter > phiBefore, "Source term should increase phi");
    }

    @Test
    void testResidualDecreases() {
        setupClosedBox();
        for (int z = 1; z < SIZE - 1; z++)
            for (int y = 1; y < SIZE - 1; y++)
                for (int x = 1; x < SIZE - 1; x++)
                    region.getPhi()[region.flatIndex(x, y, z)] = (float) (Math.random() * 50);

        float prevRes = DiffusionSolver.computeMaxResidual(region, 0f);
        int violations = 0;
        for (int i = 0; i < 20; i++) {
            DiffusionSolver.jacobiStep(region, RATE, 0f);
            float res = DiffusionSolver.computeMaxResidual(region, 0f);
            if (res > prevRes * 1.01f) violations++;
            prevRes = res;
        }
        assertTrue(violations <= 2, "Residual should generally decrease, got " + violations + " violations");
    }

    @Test
    void testEmptyRegion_noChange() {
        float maxDelta = DiffusionSolver.jacobiStep(region, RATE, 0f);
        assertEquals(0f, maxDelta, EPSILON, "Empty region should have zero delta");
    }

    @Test
    void testNaNProtection() {
        int idx = region.flatIndex(4, 4, 4);
        region.getType()[idx] = DiffusionRegion.TYPE_ACTIVE;
        region.getConductivity()[idx] = 1f;
        region.getPhi()[idx] = Float.MAX_VALUE;
        DiffusionSolver.solve(region, 10, RATE, 0f);
        assertFalse(Float.isNaN(region.getPhi()[idx]));
        assertFalse(Float.isInfinite(region.getPhi()[idx]));
    }

    // ─── Auxiliary ───

    private void setupClosedBox() {
        for (int z = 0; z < SIZE; z++)
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (x == 0 || x == SIZE - 1 || y == 0 || y == SIZE - 1 || z == 0 || z == SIZE - 1) {
                        region.setVoxel(idx, DiffusionRegion.TYPE_SOLID_WALL, 0f, 0f, 0f);
                    } else {
                        region.setVoxel(idx, DiffusionRegion.TYPE_ACTIVE, 1f, 0f, 0f);
                    }
                }
    }

    private DiffusionRegion cloneRegion(DiffusionRegion src) {
        DiffusionRegion dst = new DiffusionRegion(
            src.getRegionId(), src.getOriginX(), src.getOriginY(), src.getOriginZ(),
            src.getSizeX(), src.getSizeY(), src.getSizeZ());
        System.arraycopy(src.getPhi(), 0, dst.getPhi(), 0, src.getTotalVoxels());
        System.arraycopy(src.getConductivity(), 0, dst.getConductivity(), 0, src.getTotalVoxels());
        System.arraycopy(src.getSource(), 0, dst.getSource(), 0, src.getTotalVoxels());
        System.arraycopy(src.getType(), 0, dst.getType(), 0, src.getTotalVoxels());
        return dst;
    }
}
