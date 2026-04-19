package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.EM_FIELD}.
 *
 * <p>EM_FIELD slots feed {@code OP_AUG_SOURCE_ADD} with J² (current
 * density squared) as the payload. Parity oracle matches the kernel
 * bit-for-bit across the full 50k-voxel fixture.
 */
class EMAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("EM_FIELD wide bounds — oracle bit-identity, zero clamps")
    void sourceAddWideBounds() {
        float[] slot = AugParityBase.randomUnitSlot(0xE00F1E1DL, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];
        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        assertEquals(oracle, clamped);
        assertEquals(0, clamped);
        assertArrayEquals(srcB, srcA, 0.0f);
    }

    @Test
    @DisplayName("EM_FIELD upper-bound clamp only — count and result match oracle")
    void sourceAddUpperClampOnly() {
        /* Lower bound far below, upper bound just above median → only
         * high-J² voxels get clamped; low end is untouched. */
        float[] slot = AugParityBase.randomUnitSlot(0xE00F2222L, N);
        float lo = -1e6f;
        float hi = 0.8f;
        float[] srcA = new float[N];
        float[] srcB = new float[N];
        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N, lo, hi);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N, lo, hi);
        assertEquals(oracle, clamped);
        assertTrue(clamped > 0, "upper-bound clamp should fire on the top bin");
        assertArrayEquals(srcB, srcA, 0.0f);
    }
}
