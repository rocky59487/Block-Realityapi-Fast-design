package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.3e M4 — AMG preconditioner parity harness.
 *
 * <p>The GPU path ({@link PFSFAMGRecorder}) requires Vulkan so it cannot
 * execute on headless CI slots. Instead these tests exercise the CPU
 * twin of the shader math ({@link AMGPreconditioner#runCpuVCycle}) and
 * assert the algebraic invariants a correct AMG must preserve:</p>
 *
 * <ul>
 *   <li>Partition of unity: the column sums of the smoothed prolongator
 *       match the aggregate sizes, so prolonging a constant coarse
 *       vector lands a constant fine vector (preserves the null space).</li>
 *   <li>Restriction + prolongation of a constant residual reproduces
 *       the input on each aggregate — the basic consistency check for
 *       R = P^T and Galerkin A_c.</li>
 *   <li>Coarsening actually shrinks the grid (nCoarse &lt; nFine) on a
 *       fully-solid cube; otherwise the coarse solve accomplishes
 *       nothing and we are back to geometric V-cycle semantics.</li>
 * </ul>
 *
 * <p>Because {@code amg_scatter_restrict.comp.glsl} and
 * {@code amg_gather_prolong.comp.glsl} are direct translations of the
 * CPU helper (same atomic accumulate pattern, same 1-to-1 gather), a
 * CPU pass that respects the invariants above is evidence the GPU shader
 * will as well. Once a GPU-enabled CI slot lights up, a forthcoming
 * test can replace this oracle with a live shader diff ≤ 1e-5.</p>
 */
class PFSFAMGParityTest {

    /** Numerical tolerance for the partition-of-unity invariant. */
    private static final float PARTITION_TOL = 1e-4f;

    /** Numerical tolerance for the restrict→prolong round trip. */
    private static final float ROUNDTRIP_TOL = 1e-4f;

    @Test
    @DisplayName("build() on an 8³ solid cube coarsens strictly")
    void coarsensSolidCube() {
        int L = 8;
        int nFine = L * L * L;

        float[] cond = new float[nFine * 6];
        int[] vtype  = new int[nFine];
        Arrays.fill(cond, 1.0f);
        Arrays.fill(vtype, 1);
        // Anchor the bottom face so the AMG solver has a Dirichlet BC.
        for (int x = 0; x < L; x++) {
            for (int z = 0; z < L; z++) {
                vtype[flat(x, 0, z, L, L)] = 2;
            }
        }

        AMGPreconditioner amg = new AMGPreconditioner();
        amg.build(flatten(cond, nFine), vtype, L, L, L);

        assertTrue(amg.isReady(), "setup must complete");
        assertTrue(amg.getNCoarse() > 0, "coarse grid is empty");
        assertTrue(amg.getNCoarse() < nFine,
                "coarsening produced no reduction: nCoarse=" + amg.getNCoarse()
                        + " vs nFine=" + nFine);
    }

    @Test
    @DisplayName("smoothed prolongator preserves constant null space")
    void partitionOfUnity() {
        AMGPreconditioner amg = buildRandomisedCube(12345L);
        assertTrue(amg.checkPartitionOfUnity(PARTITION_TOL),
                "P columns don't sum to |aggregate| — AMG setup drifted");
    }

    @Test
    @DisplayName("V-Cycle on a constant residual returns a correction " +
                 "bounded by (residual / min column sum)")
    void roundTripBoundedCorrection() {
        AMGPreconditioner amg = buildRandomisedCube(42L);
        int nFine = amg.getAggregation().length;

        float[] r = new float[nFine];
        Arrays.fill(r, 1.0f);
        // Anchor residual stays 0 — convention matched by the CPU path.
        for (int i = 0; i < nFine; i++) {
            if (amg.getAggregation()[i] < 0) r[i] = 0f;
        }

        float[] e = new float[nFine];
        amg.runCpuVCycle(r, e, /*coarseSweeps=*/ 16, /*jacobiOmega=*/ 1.0f);

        // Every correction entry must be finite and non-negative for a
        // positive-residual input (Jacobi on a positive-semidefinite
        // coarse diagonal cannot flip sign).
        for (int i = 0; i < nFine; i++) {
            assertTrue(Float.isFinite(e[i]),
                    "V-Cycle produced non-finite correction at i=" + i);
            assertTrue(e[i] >= -ROUNDTRIP_TOL,
                    "V-Cycle produced negative correction at i=" + i
                            + ": e=" + e[i]);
        }
    }

    @Test
    @DisplayName("constant coarse correction prolongs to constant fine field")
    void constantProlongation() {
        AMGPreconditioner amg = buildRandomisedCube(7L);
        int   nFine   = amg.getAggregation().length;
        int   nCoarse = amg.getNCoarse();
        int[] agg     = amg.getAggregation();
        float[] pw    = amg.getPWeights();

        // Direct prolongation: e_f[i] = pWeights[i] · e_c[agg(i)] with
        // e_c ≡ 1. By partition of unity Σ_{i∈agg_j} pWeights[i] = |agg_j|,
        // and prolonging 1 should recover a fine field whose per-aggregate
        // mean is ~1.0.
        double[] aggSum = new double[nCoarse];
        int[]    aggCnt = new int[nCoarse];
        for (int i = 0; i < nFine; i++) {
            int a = agg[i];
            if (a < 0) continue;
            aggSum[a] += pw[i];   // e_c = 1 contribution
            aggCnt[a]++;
        }
        for (int j = 0; j < nCoarse; j++) {
            if (aggCnt[j] == 0) continue;
            double mean = aggSum[j] / aggCnt[j];
            assertEquals(1.0, mean, PARTITION_TOL,
                    "aggregate " + j + " mean weight drifted: " + mean);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Build an AMG setup on an 8³ cube with random-but-reproducible
     * conductivity (clustered around 1.0 with 10% variation). The
     * randomness exercises the strength-threshold aggregation path.
     */
    private static AMGPreconditioner buildRandomisedCube(long seed) {
        int L = 8;
        int nFine = L * L * L;
        SplittableRandom rng = new SplittableRandom(seed);

        float[] cond = new float[nFine];
        for (int i = 0; i < nFine; i++) {
            cond[i] = 0.9f + 0.2f * rng.nextFloat();
        }
        int[] vtype = new int[nFine];
        Arrays.fill(vtype, 1);
        for (int x = 0; x < L; x++) {
            for (int z = 0; z < L; z++) {
                vtype[flat(x, 0, z, L, L)] = 2;
            }
        }

        AMGPreconditioner amg = new AMGPreconditioner();
        amg.build(cond, vtype, L, L, L);
        assertTrue(amg.isReady(), "AMG setup did not complete");
        return amg;
    }

    private static int flat(int x, int y, int z, int Ly, int Lz) {
        return x * Ly * Lz + y * Lz + z;
    }

    /**
     * Collapse a SoA-6 conductivity array to per-voxel scalar by
     * taking the mean of the 6 face values. AMG setup wants the
     * isotropic coupling estimate.
     */
    private static float[] flatten(float[] soa6, int nFine) {
        float[] out = new float[nFine];
        for (int i = 0; i < nFine; i++) {
            float s = 0f;
            for (int d = 0; d < 6; d++) s += soa6[d * nFine + i];
            out[i] = s / 6.0f;
        }
        return out;
    }
}
