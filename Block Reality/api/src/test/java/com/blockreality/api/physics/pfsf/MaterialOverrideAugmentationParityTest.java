package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.MATERIAL_OVR}.
 *
 * <p>MATERIAL_OVR is the second consumer of {@code OP_AUG_COND_MUL}.
 * Test with bounds tuned for the damaged-block / scaffolding-cured
 * overlays that the binder advertises in its Javadoc — values in
 * {@code [0.1, 2.0]}. A zero-factor input should drop the conductivity
 * to exactly zero (not truncate to the clamp window's lower bound).
 */
class MaterialOverrideAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("MATERIAL_OVR cond_mul within published damage/reinforce window")
    void condMulDamageWindow() {
        float[] slot = AugParityBase.randomUnitSlot(0x0DEFACEDL, N);
        float[] condA = AugParityBase.randomCond6(0xDADADADAL, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyCondMul(condA, slot, N, 0.1f, 2.0f);
        int oracle  = AugParityBase.oracleCondMul(condB, slot, N, 0.1f, 2.0f);

        assertEquals(oracle, clamped);
        assertArrayEquals(condB, condA, 0.0f);
    }

    @Test
    @DisplayName("MATERIAL_OVR zero-factor slot is a 0-clamp and zeroes every slab")
    void condMulZeroFactorZeroes() {
        /* slot[i] = 0 with lo=0 → clamp is a no-op, multiply by zero
         * — conductivity[d][i] should become exactly 0 for every d.
         * This doubles as a guard against any accidental masking-in of
         * the clamp lower bound. */
        float[] slot = new float[N];  /* all zeros */
        float[] condA = AugParityBase.randomCond6(0xDEAD_BEEFL, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyCondMul(condA, slot, N, 0.0f, 2.0f);
        int oracle  = AugParityBase.oracleCondMul(condB, slot, N, 0.0f, 2.0f);

        assertEquals(oracle, clamped);
        assertEquals(0, clamped, "zero slot within [0,2] never triggers clamp");
        for (int d = 0; d < 6; ++d) {
            for (int i = 0; i < N; ++i) {
                assertEquals(0.0f, condA[d * N + i], 0.0f,
                        "cond[" + d + "][" + i + "] must zero under zero-factor");
            }
        }
    }
}
