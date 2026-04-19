package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.WIND_FIELD_3D}.
 *
 * <p>WIND_FIELD_3D feeds the unique {@code OP_AUG_WIND_3D_BIAS} kernel:
 * {@code cond[d*N + i] *= clamp(1 + k·dot(dir[d], wind[i]), lo, hi)}.
 * Three properties to prove:
 *
 * <ol>
 *   <li>With {@code k=0}, the kernel collapses to {@code cond *= 1} —
 *       conductivity is untouched and no voxel is flagged clamped.</li>
 *   <li>Every direction slab sees its own projection; pairing a +X wind
 *       with a -X direction yields a strictly different factor than the
 *       +X direction on the same voxel (proves the DIR table is wired).</li>
 *   <li>Clamp count is voxel-level (not per-direction) — matches the
 *       kernel contract.</li>
 * </ol>
 */
class Wind3DAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("WIND_3D_BIAS with k=0 is a no-op and yields zero clamps")
    void kZeroIsNoop() {
        float[] wind = AugParityBase.randomWind3d(0xA1A1A1A1L, N);
        float[] condA = AugParityBase.randomCond6(0xB2B2B2B2L, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyWind3DBias(condA, wind, N,
                0.0f, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleWind3DBias(condB, wind, N,
                0.0f, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);

        assertEquals(oracle, clamped);
        assertEquals(0, clamped);
        assertArrayEquals(condB, condA, 0.0f);
    }

    @Test
    @DisplayName("WIND_3D_BIAS with k=0.1 matches oracle bit-for-bit over 50k voxels")
    void k01MatchesOracle() {
        float[] wind = AugParityBase.randomWind3d(0xA3A3A3A3L, N);
        float[] condA = AugParityBase.randomCond6(0xB4B4B4B4L, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyWind3DBias(condA, wind, N,
                0.1f, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleWind3DBias(condB, wind, N,
                0.1f, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);

        assertEquals(oracle, clamped);
        assertArrayEquals(condB, condA, 0.0f);
    }

    @Test
    @DisplayName("WIND_3D_BIAS direction table is wired — +X wind on ±X slabs")
    void directionTableWired() {
        /* Single voxel, pure +X wind of magnitude 5. k=0.1.
         *   dir 0 (-X): 1 + 0.1 * (-1) * 5 = 0.5
         *   dir 1 (+X): 1 + 0.1 * (+1) * 5 = 1.5
         *   dir 2..5 : 1.0 (orthogonal). */
        float[] wind = { 5f, 0f, 0f };
        float[] cond = new float[6];
        for (int d = 0; d < 6; ++d) cond[d] = 1.0f;

        int clamped = PFSFAugApplicator.applyWind3DBias(cond, wind, 1,
                0.1f, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);

        assertEquals(0, clamped);
        assertEquals(0.5f, cond[0], 1e-6f, "d=0 (-X) should halve under +X wind");
        assertEquals(1.5f, cond[1], 1e-6f, "d=1 (+X) should grow to 1.5");
        for (int d = 2; d < 6; ++d) {
            assertEquals(1.0f, cond[d], 1e-6f,
                    "orthogonal direction d=" + d + " must be untouched");
        }
    }

    @Test
    @DisplayName("WIND_3D_BIAS clamp count is voxel-level, not per-direction")
    void clampCountVoxelLevel() {
        /* Narrow bounds + strong wind so nearly every voxel has at
         * least one direction clamped. Oracle counts voxels (at most
         * once each) — applicator must agree exactly. */
        float[] wind = AugParityBase.randomWind3d(0xC5C5C5C5L, N);
        float[] condA = AugParityBase.randomCond6(0xD6D6D6D6L, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyWind3DBias(condA, wind, N,
                0.5f, 0.5f, 1.5f);
        int oracle  = AugParityBase.oracleWind3DBias(condB, wind, N,
                0.5f, 0.5f, 1.5f);

        assertEquals(oracle, clamped);
        assertTrue(clamped <= N, "clamp count must be per-voxel (<= N), got " + clamped);
        assertArrayEquals(condB, condA, 0.0f);
    }
}
