package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.3d Phase 1 — Java ref ↔ native compute.v1 parity harness.
 *
 * <p>Phase 0 shipped the fixture directory scaffolding and deterministic
 * self-parity checks. Phase 1 extends the suite with Java-ref vs native
 * cross-backend parity for the four primitives now live in
 * {@code libpfsf_compute}:</p>
 *
 * <ul>
 *   <li>{@code pfsf_wind_pressure_source}</li>
 *   <li>{@code pfsf_timoshenko_moment_factor}</li>
 *   <li>{@code pfsf_normalize_soa6}</li>
 *   <li>{@code pfsf_apply_wind_bias}</li>
 * </ul>
 *
 * <p>The cross-backend tests are gated on
 * {@link NativePFSFBridge#hasComputeV1()} — on GPU-less CI runners without
 * {@code libblockreality_pfsf} they {@code skip} cleanly instead of failing,
 * so the same suite runs on every platform. When the feature probe comes
 * back {@code true} the assertions activate automatically and guard every
 * future PR against native drift.</p>
 */
class GoldenParityTest {

    /** Absolute tolerance for float-32 primitives — matches the M4 parity gate. */
    private static final float STRESS_ABS_TOL = 1e-5f;

    // ── Phase 0 sanity ──────────────────────────────────────────────────

    @Test
    @DisplayName("Fixture directory exists and is classpath-visible")
    void testFixtureDirectoryReachable() {
        URL readme = GoldenParityTest.class.getResource("/pfsf-fixtures/README.md");
        assertNotNull(readme,
                "pfsf-fixtures/README.md must be on the test classpath — see " +
                "api/src/test/resources/pfsf-fixtures/");
    }

    @Test
    @DisplayName("Wind pressure is deterministic across repeated invocations (Java ref)")
    void testWindPressureSelfParity() {
        SplittableRandom rng = new SplittableRandom(0x5F3759DFL);
        for (int i = 0; i < 256; i++) {
            float v       = (float) rng.nextDouble() * 40.0f;
            float density = 1800.0f + (float) rng.nextDouble() * 800.0f;
            boolean exp   = (i & 1) == 0;

            float a = PFSFSourceBuilder.computeWindPressureJavaRef(v, density, exp);
            float b = PFSFSourceBuilder.computeWindPressureJavaRef(v, density, exp);
            assertEquals(a, b, STRESS_ABS_TOL,
                    "Java ref wind pressure must be deterministic: v=" + v +
                    " density=" + density + " exp=" + exp);
        }
    }

    @Test
    @DisplayName("Timoshenko factor is deterministic (Java ref)")
    void testTimoshenkoSelfParity() {
        SplittableRandom rng = new SplittableRandom(0xDEADBEEFL);
        for (int i = 0; i < 256; i++) {
            float b    = 0.2f + (float) rng.nextDouble();
            float h    = 0.2f + (float) rng.nextDouble();
            int   arm  = rng.nextInt(64);
            float E    = 20.0f + (float) rng.nextDouble() * 80.0f;
            float nu   = 0.15f + (float) rng.nextDouble() * 0.2f;

            float f1 = PFSFSourceBuilder.computeTimoshenkoMomentFactorJavaRef(b, h, arm, E, nu);
            float f2 = PFSFSourceBuilder.computeTimoshenkoMomentFactorJavaRef(b, h, arm, E, nu);
            assertEquals(f1, f2, STRESS_ABS_TOL,
                    "Java ref Timoshenko must be deterministic");
            assertTrue(f1 >= 1.0f, "Timoshenko factor must be >= 1.0");
            assertTrue(f1 <= 11.0f, "Timoshenko factor must be capped at 11.0");
        }
    }

    @Test
    @DisplayName("Native bridge availability never throws from Java")
    void testNativeBridgeProbeSurvives() {
        // Must never crash, regardless of whether libblockreality_pfsf is present.
        boolean available = NativePFSFBridge.isAvailable();
        String  version   = NativePFSFBridge.getVersion();
        assertNotNull(version, "getVersion() must always return non-null");
        boolean computeV1 = NativePFSFBridge.hasComputeV1();
        assertEquals(computeV1, NativePFSFBridge.hasComputeV1(),
                "hasComputeV1() must be cached and stable");
        if (available) {
            System.out.println("[GoldenParityTest] libblockreality_pfsf loaded: " + version +
                    ", compute.v1=" + computeV1);
        } else {
            System.out.println("[GoldenParityTest] libblockreality_pfsf absent — " +
                    "Java reference path authoritative.");
        }
    }

    // ── Phase 1 — cross-backend parity (compute.v1) ────────────────────

    @Test
    @DisplayName("Native wind pressure matches Java ref bit-close (compute.v1)")
    void testWindPressureCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV1(),
                "compute.v1 unavailable — Java ref is authoritative on this runner.");

        SplittableRandom rng = new SplittableRandom(0xC0FFEEL);
        for (int i = 0; i < 1024; i++) {
            float v       = (float) rng.nextDouble() * 50.0f;
            float density = 500.0f + (float) rng.nextDouble() * 7500.0f;
            boolean exp   = (i % 3) != 0;

            float jref = PFSFSourceBuilder.computeWindPressureJavaRef(v, density, exp);
            float nat  = NativePFSFBridge.nativeWindPressureSource(v, density, exp);
            assertEquals(jref, nat, STRESS_ABS_TOL,
                    "wind_pressure parity drift: v=" + v + " density=" + density + " exp=" + exp);
        }

        // Degenerate cases — both paths must agree on exactly 0.0f.
        assertEquals(0.0f, NativePFSFBridge.nativeWindPressureSource(0.0f, 2400f, true));
        assertEquals(0.0f, NativePFSFBridge.nativeWindPressureSource(-5f,  2400f, true));
        assertEquals(0.0f, NativePFSFBridge.nativeWindPressureSource(10f,  2400f, false));
        assertEquals(0.0f, NativePFSFBridge.nativeWindPressureSource(10f,  0f,    true));
    }

    @Test
    @DisplayName("Native Timoshenko matches Java ref bit-close (compute.v1)")
    void testTimoshenkoCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV1(),
                "compute.v1 unavailable — Java ref is authoritative on this runner.");

        SplittableRandom rng = new SplittableRandom(0xBADC0FFEEL);
        for (int i = 0; i < 1024; i++) {
            float b    = 0.05f + (float) rng.nextDouble() * 2.0f;
            float h    = 0.05f + (float) rng.nextDouble() * 4.0f;
            int   arm  = rng.nextInt(128);
            float E    = 5.0f + (float) rng.nextDouble() * 200.0f;
            float nu   = 0.05f + (float) rng.nextDouble() * 0.4f;

            float jref = PFSFSourceBuilder.computeTimoshenkoMomentFactorJavaRef(b, h, arm, E, nu);
            float nat  = NativePFSFBridge.nativeTimoshenkoMomentFactor(b, h, arm, E, nu);
            assertEquals(jref, nat, STRESS_ABS_TOL,
                    "Timoshenko parity drift: b=" + b + " h=" + h + " arm=" + arm +
                    " E=" + E + " nu=" + nu);
        }

        // Boundary: arm=0 must short-circuit to 1.0f in both paths.
        assertEquals(1.0f, NativePFSFBridge.nativeTimoshenkoMomentFactor(0.3f, 0.5f, 0, 30f, 0.2f));
        assertEquals(1.0f, NativePFSFBridge.nativeTimoshenkoMomentFactor(0.3f, 0.0f, 5, 30f, 0.2f));
    }

    @Test
    @DisplayName("Native normalize_soa6 matches Java ref bit-close (compute.v1)")
    void testNormalizeSoA6CrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV1(),
                "compute.v1 unavailable — Java ref is authoritative on this runner.");

        final int N = 1024;
        SplittableRandom rng = new SplittableRandom(0x600DFEEDL);

        // Generate one shared seed-set, then feed independent copies to both paths.
        float[] srcSeed  = new float[N];
        float[] rcSeed   = new float[N];
        float[] rtSeed   = new float[N];
        float[] condSeed = new float[6 * N];
        for (int i = 0; i < N; i++) {
            srcSeed[i] = (float) (rng.nextDouble() * 20.0 - 10.0);
            rcSeed[i]  = (float) (rng.nextDouble() * 100.0);
            rtSeed[i]  = (float) (rng.nextDouble() * 20.0);
        }
        for (int i = 0; i < condSeed.length; i++) {
            condSeed[i] = (float) (rng.nextDouble() * 50.0);
        }
        // Force at least one conductivity > 1 so the normalisation branch fires.
        condSeed[rng.nextInt(condSeed.length)] = 42.0f;

        float[] srcJ  = srcSeed.clone();
        float[] rcJ   = rcSeed.clone();
        float[] rtJ   = rtSeed.clone();
        float[] condJ = condSeed.clone();
        float sigmaJ  = PFSFDataBuilder.normalizeSoA6JavaRef(srcJ, rcJ, rtJ, condJ, N);

        float[] srcN  = srcSeed.clone();
        float[] rcN   = rcSeed.clone();
        float[] rtN   = rtSeed.clone();
        float[] condN = condSeed.clone();
        float sigmaN  = NativePFSFBridge.nativeNormalizeSoA6(srcN, rcN, rtN, condN, null, N);

        assertEquals(sigmaJ, sigmaN, STRESS_ABS_TOL, "sigmaMax drift");
        for (int i = 0; i < N; i++) {
            assertEquals(srcJ[i], srcN[i], STRESS_ABS_TOL, "source drift @" + i);
            assertEquals(rcJ[i],  rcN[i],  STRESS_ABS_TOL, "rcomp drift @"  + i);
            assertEquals(rtJ[i],  rtN[i],  STRESS_ABS_TOL, "rtens drift @"  + i);
        }
        for (int i = 0; i < condJ.length; i++) {
            assertEquals(condJ[i], condN[i], STRESS_ABS_TOL, "conductivity drift @" + i);
        }
    }

    // ── Phase 2 — arm / arch / phantom edges cross-parity ──────────────

    @Test
    @DisplayName("compute_arm_map parity — Java ref vs native (compute.v2)")
    void testArmMapCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV2(),
                "compute.v2 unavailable — topology primitives skipped on this runner.");

        // Geometry: 6×4×3 cantilever with anchors on the x=0 plane.
        final int lx = 6, ly = 4, lz = 3, N = lx * ly * lz;
        byte[] members = new byte[N];
        byte[] anchors = new byte[N];
        for (int z = 0; z < lz; z++) {
            for (int y = 0; y < ly; y++) {
                for (int x = 0; x < lx; x++) {
                    int i = x + lx * (y + ly * z);
                    members[i] = 1;
                    if (x == 0 && y == 0) anchors[i] = 1; // anchor row at x=0, y=0
                }
            }
        }

        int[] ref = new int[N];
        int[] nat = new int[N];
        PFSFSourceBuilder.computeArmMapGridJavaRef(members, anchors, lx, ly, lz, ref);
        int code = NativePFSFBridge.nativeComputeArmMap(members, anchors, lx, ly, lz, nat);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code,
                "nativeComputeArmMap: " + NativePFSFBridge.PFSFResult.describe(code));
        assertArrayEquals(ref, nat, "arm map drift");

        // Sanity: anchor row has arm=0, voxels directly above anchors inherit
        // arm=0 because they cannot reach any anchor horizontally.
        assertEquals(0, nat[0], "anchor voxel must be arm=0");
        assertEquals(0, nat[1 * lx + 0], "unreachable-by-horizontal-path voxel must be arm=0");
    }

    @Test
    @DisplayName("compute_arch_factor_map parity — Java ref vs native (compute.v2)")
    void testArchFactorCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV2(),
                "compute.v2 unavailable — topology primitives skipped on this runner.");

        // Two-pillar arch: anchor columns at x=0 and x=Lx-1, bridged at the top.
        final int lx = 7, ly = 3, lz = 2, N = lx * ly * lz;
        byte[] members = new byte[N];
        byte[] anchors = new byte[N];
        for (int z = 0; z < lz; z++) {
            for (int y = 0; y < ly; y++) {
                for (int x = 0; x < lx; x++) {
                    int i = x + lx * (y + ly * z);
                    members[i] = 1;
                    if ((x == 0 || x == lx - 1) && y == 0) anchors[i] = 1;
                }
            }
        }

        float[] ref = new float[N];
        float[] nat = new float[N];
        PFSFSourceBuilder.computeArchFactorMapGridJavaRef(members, anchors, lx, ly, lz, ref);
        int code = NativePFSFBridge.nativeComputeArchFactorMap(members, anchors, lx, ly, lz, nat);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code,
                "nativeComputeArchFactorMap: " + NativePFSFBridge.PFSFResult.describe(code));
        for (int i = 0; i < N; i++) {
            assertEquals(ref[i], nat[i], STRESS_ABS_TOL, "arch factor drift @" + i);
            assertTrue(nat[i] >= 0.0f && nat[i] <= 1.0f,
                    "arch factor must stay in [0,1]; got " + nat[i]);
        }
    }

    @Test
    @DisplayName("compute_arch_factor_map returns zeros when < 2 anchor groups (compute.v2)")
    void testArchFactorSingleGroup() {
        assumeTrue(NativePFSFBridge.hasComputeV2(),
                "compute.v2 unavailable — topology primitives skipped on this runner.");

        final int lx = 4, ly = 2, lz = 2, N = lx * ly * lz;
        byte[] members = new byte[N];
        byte[] anchors = new byte[N];
        for (int i = 0; i < N; i++) members[i] = 1;
        // Single horizontal anchor strip → one union-find group → all-zero factor.
        for (int x = 0; x < lx; x++) anchors[x] = 1;

        float[] nat = new float[N];
        int code = NativePFSFBridge.nativeComputeArchFactorMap(members, anchors, lx, ly, lz, nat);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        for (int i = 0; i < N; i++) {
            assertEquals(0.0f, nat[i], 0.0f, "single-group arch factor must be 0 @" + i);
        }
    }

    @Test
    @DisplayName("inject_phantom_edges parity — Java ref vs native (compute.v2)")
    void testPhantomEdgesCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV2(),
                "compute.v2 unavailable — topology primitives skipped on this runner.");

        final int lx = 5, ly = 5, lz = 5, N = lx * ly * lz;
        SplittableRandom rng = new SplittableRandom(0xF1E2D3C4L);
        byte[] members = new byte[N];
        float[] rcomp   = new float[N];
        for (int i = 0; i < N; i++) {
            members[i] = (byte) (rng.nextInt(3) == 0 ? 0 : 1); // ~66% fill
            rcomp[i]   = 0.5f + (float) rng.nextDouble();
        }
        float[] condSeed = new float[6 * N];
        // Leave most slots at zero so phantom injection actually writes.
        // Populate a few real face edges so we verify "non-zero slot preserved".
        for (int i = 0; i < 6 * N; i++) {
            condSeed[i] = rng.nextInt(10) == 0 ? 0.42f : 0.0f;
        }

        float edgePen   = 0.30f;
        float cornerPen = 0.15f;

        float[] condJ = condSeed.clone();
        int injJ = PFSFSourceBuilder.injectPhantomEdgesGridJavaRef(
                members, condJ, rcomp, lx, ly, lz, edgePen, cornerPen);

        float[] condN = condSeed.clone();
        int injN = NativePFSFBridge.nativeInjectPhantomEdges(
                members, condN, rcomp, lx, ly, lz, edgePen, cornerPen);

        assertEquals(injJ, injN, "phantom-edge injection count drift");
        for (int i = 0; i < condJ.length; i++) {
            assertEquals(condJ[i], condN[i], STRESS_ABS_TOL,
                    "conductivity drift @" + i + " (member=" + members[i % N] + ")");
        }
    }

    // ── Phase 3 — morton / downsample / tiled_layout cross-parity ─────

    @Test
    @DisplayName("morton_encode/decode parity + round-trip (compute.v3)")
    void testMortonCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV3(),
                "compute.v3 unavailable — Morton primitive skipped on this runner.");

        SplittableRandom rng = new SplittableRandom(0x1234ABCDEFL);
        int[] outXYZ = new int[3];
        for (int i = 0; i < 1024; i++) {
            int x = rng.nextInt(1024);
            int y = rng.nextInt(1024);
            int z = rng.nextInt(1024);

            int refCode = MortonCode.encodeJavaRef(x, y, z);
            int natCode = NativePFSFBridge.nativeMortonEncode(x, y, z);
            assertEquals(refCode, natCode,
                    "morton encode drift @" + x + "," + y + "," + z);

            NativePFSFBridge.nativeMortonDecode(natCode, outXYZ);
            assertEquals(x, outXYZ[0], "decode X round-trip drift");
            assertEquals(y, outXYZ[1], "decode Y round-trip drift");
            assertEquals(z, outXYZ[2], "decode Z round-trip drift");

            assertEquals(MortonCode.decodeXJavaRef(natCode), outXYZ[0], "decodeX parity");
            assertEquals(MortonCode.decodeYJavaRef(natCode), outXYZ[1], "decodeY parity");
            assertEquals(MortonCode.decodeZJavaRef(natCode), outXYZ[2], "decodeZ parity");
        }

        // Boundary — (0,0,0) and (1023,1023,1023).
        assertEquals(0, NativePFSFBridge.nativeMortonEncode(0, 0, 0));
        assertEquals(MortonCode.encodeJavaRef(1023, 1023, 1023),
                NativePFSFBridge.nativeMortonEncode(1023, 1023, 1023));
        NativePFSFBridge.nativeMortonDecode(0, outXYZ);
        assertEquals(0, outXYZ[0]);
        assertEquals(0, outXYZ[1]);
        assertEquals(0, outXYZ[2]);
    }

    @Test
    @DisplayName("downsample_2to1 parity — majority-vote anchor > solid > air (compute.v3)")
    void testDownsample2to1CrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV3(),
                "compute.v3 unavailable — downsample primitive skipped.");

        // Odd dims → coarse = (fine+1)/2 exercises non-tile-aligned tail.
        final int lxf = 5, lyf = 4, lzf = 3;
        final int lxc = (lxf + 1) / 2;
        final int lyc = (lyf + 1) / 2;
        final int lzc = (lzf + 1) / 2;
        final int Nf = lxf * lyf * lzf;
        final int Nc = lxc * lyc * lzc;

        SplittableRandom rng = new SplittableRandom(0xDA7A_DA7AL);
        float[] fine     = new float[Nf];
        byte[]  fineType = new byte[Nf];
        for (int i = 0; i < Nf; i++) {
            fine[i]     = (float) rng.nextDouble() * 10.0f;
            fineType[i] = (byte) rng.nextInt(3); // 0=air, 1=solid, 2=anchor
        }

        float[] coarseN     = new float[Nc];
        byte[]  coarseTypeN = new byte[Nc];
        int code = NativePFSFBridge.nativeDownsample2to1(
                fine, fineType, lxf, lyf, lzf, coarseN, coarseTypeN);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code,
                "nativeDownsample2to1: " + NativePFSFBridge.PFSFResult.describe(code));

        // Reference — recompute the 2:1 restriction independently.
        for (int zc = 0; zc < lzc; zc++) {
            for (int yc = 0; yc < lyc; yc++) {
                for (int xc = 0; xc < lxc; xc++) {
                    float sum = 0.0f;
                    int contrib = 0;
                    int cntAir = 0, cntSolid = 0, cntAnchor = 0;
                    for (int dz = 0; dz < 2; dz++) {
                        int zf = zc * 2 + dz; if (zf >= lzf) continue;
                        for (int dy = 0; dy < 2; dy++) {
                            int yf = yc * 2 + dy; if (yf >= lyf) continue;
                            for (int dx = 0; dx < 2; dx++) {
                                int xf = xc * 2 + dx; if (xf >= lxf) continue;
                                int fi = xf + lxf * (yf + lyf * zf);
                                sum += fine[fi];
                                contrib++;
                                int t = fineType[fi] & 0xFF;
                                if      (t == 2) cntAnchor++;
                                else if (t == 1) cntSolid++;
                                else             cntAir++;
                            }
                        }
                    }
                    int ci = xc + lxc * (yc + lyc * zc);
                    float expected = contrib > 0 ? sum / (float) contrib : 0.0f;
                    assertEquals(expected, coarseN[ci], STRESS_ABS_TOL,
                            "downsample value drift @(" + xc + "," + yc + "," + zc + ")");
                    byte expectedType;
                    if (cntAnchor >= cntSolid && cntAnchor >= cntAir) expectedType = 2;
                    else if (cntSolid  >= cntAir)                      expectedType = 1;
                    else                                                expectedType = 0;
                    assertEquals(expectedType, coarseTypeN[ci],
                            "downsample type vote drift @(" + xc + "," + yc + "," + zc + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("downsample_2to1 honours anchor tie-break (compute.v3)")
    void testDownsample2to1AnchorTieBreak() {
        assumeTrue(NativePFSFBridge.hasComputeV3(),
                "compute.v3 unavailable — downsample primitive skipped.");

        // 2×2×2 fine grid with one anchor, one solid, two air — anchor wins
        // by the {anchor > solid > air} priority rule.
        float[] fine = new float[]{ 1, 2, 3, 4, 5, 6, 7, 8 };
        byte[]  ft   = new byte[]{  2, 1, 0, 0, 0, 0, 0, 0 };
        float[] coarse = new float[1];
        byte[]  ct     = new byte[1];
        int code = NativePFSFBridge.nativeDownsample2to1(fine, ft, 2, 2, 2, coarse, ct);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertEquals((1f+2f+3f+4f+5f+6f+7f+8f) / 8.0f, coarse[0], STRESS_ABS_TOL);
        assertEquals((byte) 2, ct[0], "anchor must win the majority-vote tie-break");
    }

    @Test
    @DisplayName("tiled_layout_build parity vs direct reference, tile=8 (compute.v3)")
    void testTiledLayoutBuildCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV3(),
                "compute.v3 unavailable — tiled layout primitive skipped.");

        final int lx = 10, ly = 8, lz = 9;
        final int tile  = 8;
        final int ntx   = (lx + tile - 1) / tile;
        final int nty   = (ly + tile - 1) / tile;
        final int ntz   = (lz + tile - 1) / tile;
        final int tile3 = tile * tile * tile;
        final int N     = lx * ly * lz;

        SplittableRandom rng = new SplittableRandom(0xDEADF00DL);
        float[] linear = new float[N];
        for (int i = 0; i < N; i++) linear[i] = (float) rng.nextDouble() * 100.0f;

        float[] out = new float[ntx * nty * ntz * tile3];
        int code = NativePFSFBridge.nativeTiledLayoutBuild(
                linear, lx, ly, lz, tile, out);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);

        // Reference — trailing slots outside source bounds stay 0 (header contract).
        float[] expected = new float[out.length];
        for (int tz = 0; tz < ntz; tz++) {
            for (int ty = 0; ty < nty; ty++) {
                for (int tx = 0; tx < ntx; tx++) {
                    int tileBase = (tz * nty * ntx + ty * ntx + tx) * tile3;
                    for (int iz = 0; iz < tile; iz++) {
                        int gz = tz * tile + iz; if (gz >= lz) continue;
                        for (int iy = 0; iy < tile; iy++) {
                            int gy = ty * tile + iy; if (gy >= ly) continue;
                            for (int ix = 0; ix < tile; ix++) {
                                int gx = tx * tile + ix; if (gx >= lx) continue;
                                int src = gx + lx * (gy + ly * gz);
                                int dst = tileBase + (iz * tile + iy) * tile + ix;
                                expected[dst] = linear[src];
                            }
                        }
                    }
                }
            }
        }
        assertArrayEquals(expected, out, 0.0f, "tiled layout drift");
    }

    @Test
    @DisplayName("tiled_layout_build is a no-op for tile != 8 (compute.v3)")
    void testTiledLayoutNonEightNoop() {
        assumeTrue(NativePFSFBridge.hasComputeV3(),
                "compute.v3 unavailable — tiled layout primitive skipped.");

        final int lx = 4, ly = 4, lz = 4;
        float[] linear = new float[lx * ly * lz];
        for (int i = 0; i < linear.length; i++) linear[i] = i + 1.0f;
        // Size the buffer for tile=4 so the C++ side has a valid non-null out
        // pointer — but expect it to remain zeroed because only tile=8 is
        // wired up in Phase 3a.
        float[] out = new float[lx * ly * lz];
        int code = NativePFSFBridge.nativeTiledLayoutBuild(linear, lx, ly, lz, 4, out);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        for (float v : out) assertEquals(0.0f, v, 0.0f,
                "tile != 8 must leave output untouched in Phase 3a");
    }

    @Test
    @DisplayName("Native normalize_soa6 no-op when sigmaMax <= 1.0f (compute.v1)")
    void testNormalizeSoA6NoopCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV1(),
                "compute.v1 unavailable — Java ref is authoritative on this runner.");

        final int N = 64;
        float[] src  = new float[N];
        float[] rc   = new float[N];
        float[] rt   = new float[N];
        float[] cond = new float[6 * N];
        for (int i = 0; i < N; i++) {
            src[i] = 1.0f;
            rc[i]  = 2.0f;
            rt[i]  = 3.0f;
        }
        // All conductivity entries <= 1 → must be a no-op.
        for (int i = 0; i < cond.length; i++) cond[i] = 0.5f;

        float sigma = NativePFSFBridge.nativeNormalizeSoA6(src, rc, rt, cond, null, N);
        assertEquals(1.0f, sigma, STRESS_ABS_TOL, "sigmaMax must clamp to 1.0f");
        for (int i = 0; i < N; i++) {
            assertEquals(1.0f, src[i], STRESS_ABS_TOL);
            assertEquals(2.0f, rc[i],  STRESS_ABS_TOL);
            assertEquals(3.0f, rt[i],  STRESS_ABS_TOL);
        }
        for (int i = 0; i < cond.length; i++) {
            assertEquals(0.5f, cond[i], STRESS_ABS_TOL);
        }
    }
}
