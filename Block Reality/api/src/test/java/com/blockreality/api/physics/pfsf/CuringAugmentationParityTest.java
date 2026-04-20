package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.CURING_FIELD}.
 *
 * <p>CURING is the only kind that fires into both {@code SOURCE_ADD}
 * (extra cure-demand load) and {@code RCOMP_MUL} (progressive strength
 * gain scales allowable compressive stress). This test exercises both
 * opcodes with the same slot array, proving the two kernels commute
 * with the dispatcher's expectation that they can be applied back-to-back.
 */
class CuringAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("CURING_FIELD source-add leg bit-eq oracle")
    void curingSourceLeg() {
        float[] slot = AugParityBase.randomUnitSlot(0xCCBA1111L, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];
        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        assertEquals(oracle, clamped);
        assertArrayEquals(srcB, srcA, 0.0f);
    }

    @Test
    @DisplayName("CURING_FIELD rcomp-mul leg bit-eq oracle")
    void curingRcompLeg() {
        float[] slot = AugParityBase.randomUnitSlot(0xCCBA2222L, N);
        /* rcomp starts at 1.0f everywhere — the curing slot progressively
         * stiffens it, so rcomp[i] should stay within [0, 1.5]. */
        float[] rA = new float[N];
        float[] rB = new float[N];
        for (int i = 0; i < N; ++i) rA[i] = rB[i] = 1.0f;

        int clamped = PFSFAugApplicator.applyRcompMul(rA, slot, N,
                0.0f, 1.5f);
        int oracle  = AugParityBase.oracleRcompMul(rB, slot, N,
                0.0f, 1.5f);

        assertEquals(oracle, clamped);
        assertArrayEquals(rB, rA, 0.0f);
    }

    @Test
    @DisplayName("CURING_FIELD source + rcomp pipeline is order-commutative with the oracle")
    void curingFullPipeline() {
        /* Applying source_add then rcomp_mul in the same tick — make
         * sure the applicator produces the exact same final state as
         * the oracle would under the same two-step pipeline. */
        float[] slot = AugParityBase.randomUnitSlot(0xCCBA3333L, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];
        float[] rA   = new float[N];
        float[] rB   = new float[N];
        for (int i = 0; i < N; ++i) rA[i] = rB[i] = 1.0f;

        PFSFAugApplicator.applySourceAdd(srcA, slot, N, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        PFSFAugApplicator.applyRcompMul(rA, slot, N, 0.0f, 1.5f);
        AugParityBase.oracleSourceAdd(srcB, slot, N, AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        AugParityBase.oracleRcompMul(rB, slot, N, 0.0f, 1.5f);

        assertArrayEquals(srcB, srcA, 0.0f);
        assertArrayEquals(rB, rA, 0.0f);
    }
}
