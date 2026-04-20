package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.FUSION_MASK}.
 *
 * <p>FUSION_MASK drives {@code OP_AUG_COND_MUL}: every conductivity
 * direction slab ({@code cond[d*N + i]}, d=0..5) is multiplied by a
 * per-voxel clamp factor. The clamp-count contract sampled the d=0
 * pass only — this test verifies that invariant along with bit-eq of
 * all six slabs.
 */
class FusionAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("FUSION_MASK applies the same factor to all 6 cond slabs")
    void condMulAllSlabs() {
        float[] slot = AugParityBase.randomUnitSlot(0xFA51011L, N);
        float[] condA = AugParityBase.randomCond6(0x5EED_C01DL, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyCondMul(condA, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleCondMul(condB, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);

        assertEquals(oracle, clamped);
        assertArrayEquals(condB, condA, 0.0f, "all 6 slabs must match the oracle");
    }

    @Test
    @DisplayName("FUSION_MASK clamp count reports d=0 pass only — matches kernel contract")
    void condMulClampCountContract() {
        /* Narrow bounds so many voxels clamp — oracle's count uses the
         * d=0 pass. Running the applicator against the same slot must
         * report the identical count. */
        float[] slot = AugParityBase.randomSlot(0xFA52022L, N);
        float[] condA = AugParityBase.randomCond6(0x5EED_F00DL, N);
        float[] condB = condA.clone();

        int clamped = PFSFAugApplicator.applyCondMul(condA, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);
        int oracle  = AugParityBase.oracleCondMul(condB, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);
        assertEquals(oracle, clamped);
        assertTrue(clamped <= N, "clamp count cannot exceed voxel count");
        assertArrayEquals(condB, condA, 0.0f);
    }
}
