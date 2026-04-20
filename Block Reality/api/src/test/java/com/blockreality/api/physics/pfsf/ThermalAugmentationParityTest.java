package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augparity.AugParityBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — parity test for {@code AugKind.THERMAL_FIELD}.
 *
 * <p>THERMAL_FIELD slots feed {@code OP_AUG_SOURCE_ADD}: the kernel
 * adds a per-voxel {@code clamp(slot[i], lo, hi)} to the source array.
 * The test diffs {@link PFSFAugApplicator#applySourceAdd} against an
 * independent in-line oracle for bit-identity and clamp-count contract.
 *
 * <p>Seeded with a fixed seed so reruns stay deterministic; the data
 * generator produces values in [-3, 3] so the narrow-bound leg fires
 * clamps on roughly 1/3 of voxels — enough to verify the count is
 * tracked per voxel, not aggregated.
 */
class ThermalAugmentationParityTest {

    private static final int N = AugParityBase.N;

    @Test
    @DisplayName("THERMAL_FIELD wide bounds — no clamp, result bit-eq oracle")
    void sourceAddWideBounds() {
        float[] slot = AugParityBase.randomSlot(0xC0FFEE01L, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];

        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N,
                AugParityBase.WIDE_LO, AugParityBase.WIDE_HI);

        assertEquals(oracle, clamped, "clamp count must match oracle (wide bounds → 0)");
        assertEquals(0, clamped, "no clamp expected with wide bounds");
        assertArrayEquals(srcB, srcA, 0.0f, "source array bit-identity");
    }

    @Test
    @DisplayName("THERMAL_FIELD narrow bounds — clamped count + values match oracle")
    void sourceAddNarrowBounds() {
        float[] slot = AugParityBase.randomSlot(0xC0FFEE02L, N);
        float[] srcA = new float[N];
        float[] srcB = new float[N];

        int clamped = PFSFAugApplicator.applySourceAdd(srcA, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);
        int oracle  = AugParityBase.oracleSourceAdd(srcB, slot, N,
                AugParityBase.NARROW_LO, AugParityBase.NARROW_HI);

        assertEquals(oracle, clamped,
                "clamp count must match oracle exactly");
        assertTrue(clamped > N / 10,
                "narrow bounds on random data should clamp a nontrivial fraction — got " + clamped);
        assertArrayEquals(srcB, srcA, 0.0f, "source array bit-identity");
    }

    @Test
    @DisplayName("THERMAL_FIELD null/empty/malformed guards — all return 0 and leave source untouched")
    void sourceAddGuards() {
        float[] src = new float[] { 1f, 2f, 3f };
        float[] slot = new float[] { 10f, 20f, 30f };

        assertEquals(0, PFSFAugApplicator.applySourceAdd(null, slot, 3, 0f, 1f));
        assertEquals(0, PFSFAugApplicator.applySourceAdd(src, null, 3, 0f, 1f));
        assertEquals(0, PFSFAugApplicator.applySourceAdd(src, slot, 0,  0f, 1f));
        assertEquals(0, PFSFAugApplicator.applySourceAdd(src, slot, -1, 0f, 1f));
        /* hi < lo — the dispatcher should have rejected this upstream but
         * the kernel defends in depth and is a no-op. */
        assertEquals(0, PFSFAugApplicator.applySourceAdd(src, slot, 3, 5f, 0f));
        assertArrayEquals(new float[] { 1f, 2f, 3f }, src, 0.0f,
                "source must be untouched under any guard trip");
    }
}
