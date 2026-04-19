package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.FLUID_PRESSURE}.
 *
 * <p>FLUID_PRESSURE slots feed {@code OP_AUG_SOURCE_ADD}. Independent
 * of {@link ThermalAugmentationParityTest} — same kernel semantics,
 * different seed, so a same-backend drift affecting only one SPI
 * would still surface here.
 */
class FluidAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("FLUID_PRESSURE narrow bounds — oracle bit-identity")
    void sourceAddNarrowBounds() {
        float[] slot = AugParityBase.randomSlot(0xF1Ab1234L, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];

        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);

        assertEquals(oracle, clamped);
        assertArrayEquals(srcB, srcA, 0.0f);
    }

    @Test
    @DisplayName("FLUID_PRESSURE accumulates when source already non-zero")
    void sourceAddAccumulates() {
        /* SOURCE_ADD is += — seed the source with a non-zero baseline to
         * confirm the operator is additive, not overwrite. */
        float[] slot = AugParityBase.randomSlot(0xF1Ab5678L, N);
        float[] baseA = new float[N];
        float[] baseB = new float[N];
        for (int i = 0; i < N; ++i) {
            baseA[i] = baseB[i] = (float) Math.sin(i * 0.001);
        }
        PFSFAugApplicator.applySourceAdd(baseA, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        AugParityBase.oracleSourceAdd(baseB, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        assertArrayEquals(baseB, baseA, 0.0f);
    }
}
