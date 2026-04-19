package com.blockreality.api.physics.pfsf;

/**
 * v0.4 M2f — pure-Java mirror of {@code libpfsf_compute::pfsf::aug}.
 *
 * <p>Bit-exact reference for the four aug opcode kernels that the
 * {@code plan_dispatcher} invokes when consuming {@code OP_AUG_SOURCE_ADD},
 * {@code OP_AUG_COND_MUL}, {@code OP_AUG_RCOMP_MUL},
 * {@code OP_AUG_WIND_3D_BIAS}. {@code GoldenParityTest} and the
 * per-kind {@code *AugmentationParityTest}s diff the native output
 * against this class.
 *
 * <p>The ops live here rather than in {@link PFSFSourceBuilder} /
 * {@link PFSFConductivity} because they're plan-stage deltas applied
 * after the base source/cond arrays are built — not first-class
 * {@code builder} concerns. Keeping them in one self-contained TU also
 * makes the parity oracle trivial to inspect when a test fails.
 *
 * <p>Semantic contract (shared with {@code aug_kernels.cpp}):
 * <ul>
 *   <li>Each per-voxel sample is clamped to {@code [lo, hi]} before use.</li>
 *   <li>The return value is the number of voxels whose raw slot sample
 *       was actually clamped (useful for trace-warn aggregation).</li>
 *   <li>{@code null} / zero-length inputs are no-ops returning 0, matching
 *       the {@code cond == nullptr || slot == nullptr || n <= 0} guard
 *       in the native kernel.</li>
 *   <li>Malformed bounds ({@code hi < lo}) are no-ops; the dispatcher
 *       validates this upstream and we mirror the kernel for defence
 *       in depth.</li>
 * </ul>
 */
public final class PFSFAugApplicator {

    /* Face-normal unit vectors in {@code pfsf_direction} order —
     *   0=-X, 1=+X, 2=-Y, 3=+Y, 4=-Z, 5=+Z.
     * Parallel arrays match the C++ kernel so future SIMD parity
     * diffs can inspect the oracle against the same layout. */
    private static final float[] DIR_X = { -1f, +1f,  0f,  0f,  0f,  0f };
    private static final float[] DIR_Y = {  0f,  0f, -1f, +1f,  0f,  0f };
    private static final float[] DIR_Z = {  0f,  0f,  0f,  0f, -1f, +1f };

    private PFSFAugApplicator() {}

    private static float clampf(float v, float lo, float hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    /**
     * Mirrors {@code pfsf::aug::source_add}.
     * {@code source[i] += clamp(slot[i], lo, hi)}.
     *
     * @return number of voxels whose raw slot was clamped.
     */
    public static int applySourceAdd(float[] source, float[] slot,
                                      int n, float lo, float hi) {
        if (source == null || slot == null || n <= 0) return 0;
        if (hi < lo) return 0;
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            float raw = slot[i];
            float cl  = clampf(raw, lo, hi);
            if (raw != cl) ++clamped;
            source[i] += cl;
        }
        return clamped;
    }

    /**
     * Mirrors {@code pfsf::aug::cond_mul}. Multiplies every direction
     * slab by the per-voxel clamp factor; counts clamp events once per
     * voxel (on the d=0 pass, matching the C kernel).
     *
     * <p>{@code cond} is the SoA-6 flat array: {@code cond[d*n + i]}.
     */
    public static int applyCondMul(float[] cond, float[] slot,
                                    int n, float lo, float hi) {
        if (cond == null || slot == null || n <= 0) return 0;
        if (hi < lo) return 0;
        int clamped = 0;
        for (int d = 0; d < 6; ++d) {
            int slabBase = d * n;
            for (int i = 0; i < n; ++i) {
                float raw = slot[i];
                float cl  = clampf(raw, lo, hi);
                if (d == 0 && raw != cl) ++clamped;
                cond[slabBase + i] *= cl;
            }
        }
        return clamped;
    }

    /**
     * Mirrors {@code pfsf::aug::rcomp_mul}.
     * {@code rcomp[i] *= clamp(slot[i], lo, hi)}.
     */
    public static int applyRcompMul(float[] rcomp, float[] slot,
                                     int n, float lo, float hi) {
        if (rcomp == null || slot == null || n <= 0) return 0;
        if (hi < lo) return 0;
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            float raw = slot[i];
            float cl  = clampf(raw, lo, hi);
            if (raw != cl) ++clamped;
            rcomp[i] *= cl;
        }
        return clamped;
    }

    /**
     * Mirrors {@code pfsf::aug::wind_3d_bias}. For each voxel:
     * {@code cond[d*n+i] *= clamp(1 + k*dot(dir[d], wind[i]), lo, hi)}.
     *
     * @param wind3d flat array of 3 floats per voxel (wx, wy, wz).
     * @return number of voxels where at least one direction's raw factor
     *         got clamped (voxel-level, not per-direction).
     */
    public static int applyWind3DBias(float[] cond, float[] wind3d,
                                       int n, float k, float lo, float hi) {
        if (cond == null || wind3d == null || n <= 0) return 0;
        if (hi < lo) return 0;
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            int tri = i * 3;
            float wx = wind3d[tri];
            float wy = wind3d[tri + 1];
            float wz = wind3d[tri + 2];

            boolean voxelClamped = false;
            for (int d = 0; d < 6; ++d) {
                float dotW = DIR_X[d] * wx + DIR_Y[d] * wy + DIR_Z[d] * wz;
                float raw  = 1.0f + k * dotW;
                float cl   = clampf(raw, lo, hi);
                if (raw != cl) voxelClamped = true;
                cond[d * n + i] *= cl;
            }
            if (voxelClamped) ++clamped;
        }
        return clamped;
    }
}
