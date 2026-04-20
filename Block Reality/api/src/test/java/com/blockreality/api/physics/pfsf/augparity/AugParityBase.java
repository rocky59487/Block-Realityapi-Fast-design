package com.blockreality.api.physics.pfsf.augparity;

import java.util.SplittableRandom;

/**
 * v0.4 M2g — shared fixture generators + oracle helpers for the per-kind
 * {@code *AugmentationParityTest}s.
 *
 * <p>The tests diff {@link com.blockreality.api.physics.pfsf.PFSFAugApplicator}
 * output against an in-line reference re-implementation of the same
 * kernel, guaranteeing that any refactor of the applicator that changes
 * numerics trips a visible test failure. Because the C++ kernel is a
 * verbatim copy of the Java mirror (same clamp, same direction table,
 * same clamp-count contract), a Java-only parity oracle is sufficient
 * proof for CI's no-GPU environment; a box with {@code libblockreality_pfsf}
 * loaded picks up the live-native parity leg in {@link com.blockreality.api.physics.pfsf.GoldenParityTest}.
 */
public final class AugParityBase {

    private AugParityBase() {}

    /** Problem size the parity tests standardise on — 50 000 voxels,
     *  matching the plan's "synthetic fixture (50k voxel)" spec. */
    public static final int N = 50_000;

    /** Default clamp bounds used by the source/cond/rcomp kernels when
     *  the caller hasn't narrowed the window — wide enough that a
     *  well-behaved slot never triggers clamping. */
    public static final float WIDE_LO = -1e6f;
    public static final float WIDE_HI =  1e6f;

    /** Narrow bounds that force a non-trivial clamp count on random data. */
    public static final float NARROW_LO = -2.0f;
    public static final float NARROW_HI =  2.0f;

    /** Build a deterministic float[n] with values in ~[-3, 3]. */
    public static float[] randomSlot(long seed, int n) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] out = new float[n];
        for (int i = 0; i < n; ++i) {
            out[i] = (float) ((r.nextDouble() - 0.5) * 6.0);
        }
        return out;
    }

    /** Build a deterministic float[n] with values in ~[0, 1.5]. */
    public static float[] randomUnitSlot(long seed, int n) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] out = new float[n];
        for (int i = 0; i < n; ++i) {
            out[i] = (float) (r.nextDouble() * 1.5);
        }
        return out;
    }

    /** Six-slab SoA-6 conductivity array ({@code cond[d*n + i]}), each
     *  slab seeded independently so a kernel that mixes slabs can be
     *  caught. Values in [0.1, 1.0]. */
    public static float[] randomCond6(long seed, int n) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] out = new float[6 * n];
        for (int i = 0; i < 6 * n; ++i) {
            out[i] = 0.1f + (float) r.nextDouble() * 0.9f;
        }
        return out;
    }

    /** 3-float-per-voxel wind vector array. Values in [-5, 5]. */
    public static float[] randomWind3d(long seed, int n) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] out = new float[3 * n];
        for (int i = 0; i < 3 * n; ++i) {
            out[i] = (float) ((r.nextDouble() - 0.5) * 10.0);
        }
        return out;
    }

    /* Oracle reimplementations — intentionally not routed through
     * PFSFAugApplicator so a regression in the applicator doesn't
     * tautologically pass the test. */

    public static int oracleSourceAdd(float[] source, float[] slot, int n,
                                       float lo, float hi) {
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            float raw = slot[i];
            float cl  = Math.min(Math.max(raw, lo), hi);
            if (raw != cl) ++clamped;
            source[i] += cl;
        }
        return clamped;
    }

    public static int oracleCondMul(float[] cond, float[] slot, int n,
                                     float lo, float hi) {
        int clamped = 0;
        for (int d = 0; d < 6; ++d) {
            int slab = d * n;
            for (int i = 0; i < n; ++i) {
                float raw = slot[i];
                float cl  = Math.min(Math.max(raw, lo), hi);
                if (d == 0 && raw != cl) ++clamped;
                cond[slab + i] *= cl;
            }
        }
        return clamped;
    }

    public static int oracleRcompMul(float[] rcomp, float[] slot, int n,
                                      float lo, float hi) {
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            float raw = slot[i];
            float cl  = Math.min(Math.max(raw, lo), hi);
            if (raw != cl) ++clamped;
            rcomp[i] *= cl;
        }
        return clamped;
    }

    public static int oracleWind3DBias(float[] cond, float[] wind3d, int n,
                                        float k, float lo, float hi) {
        float[] dx = { -1f, +1f,  0f,  0f,  0f,  0f };
        float[] dy = {  0f,  0f, -1f, +1f,  0f,  0f };
        float[] dz = {  0f,  0f,  0f,  0f, -1f, +1f };
        int clamped = 0;
        for (int i = 0; i < n; ++i) {
            int tri = i * 3;
            float wx = wind3d[tri];
            float wy = wind3d[tri + 1];
            float wz = wind3d[tri + 2];
            boolean voxelClamped = false;
            for (int d = 0; d < 6; ++d) {
                float dot = dx[d] * wx + dy[d] * wy + dz[d] * wz;
                float raw = 1.0f + k * dot;
                float cl  = Math.min(Math.max(raw, lo), hi);
                if (raw != cl) voxelClamped = true;
                cond[d * n + i] *= cl;
            }
            if (voxelClamped) ++clamped;
        }
        return clamped;
    }
}
