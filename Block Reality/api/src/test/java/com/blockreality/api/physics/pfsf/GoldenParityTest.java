package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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

    /**
     * v0.3e M1 — CI-side enforcement.
     *
     * <p>When {@code -Dpfsf.native.required=true} is passed (as the
     * {@code build-native} workflow does), the per-test {@code assumeTrue}
     * skip semantics must escalate to {@code fail}: if the .so failed to
     * load on a runner that explicitly requested native execution, every
     * parity test in this class would silently skip and CI would still be
     * green — which is precisely the blind spot this gate closes.</p>
     *
     * <p>The local/dev path (no sysprop set) keeps the original skip
     * semantics so machines without a built {@code libblockreality_pfsf}
     * still run the Java-ref deterministic sanity checks.</p>
     */
    @BeforeAll
    static void enforceNativeRequirement() {
        if (!Boolean.getBoolean("pfsf.native.required")) return;
        assertTrue(NativePFSFBridge.isAvailable(),
                "pfsf.native.required=true but libblockreality_pfsf failed to load. " +
                "Confirm the CMake build produced the .so, java.library.path is " +
                "pointing at the stage dir, and Vulkan SDK is installed on the runner.");
        // When the library loaded we expect every compute.vN flag from the
        // frozen ABI surface to be live. A mismatch here means a stale .so
        // slipped past the gate and must fail loudly, not skip.
        assertTrue(NativePFSFBridge.hasComputeV1(), "compute.v1 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV2(), "compute.v2 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV3(), "compute.v3 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV4(), "compute.v4 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV5(), "compute.v5 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV6(), "compute.v6 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV7(), "compute.v7 unavailable under required-native mode");
        assertTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable under required-native mode");
    }

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

    // ── Phase 4 — diagnostics cross-parity (compute.v4) ─────────────────

    @Test
    @DisplayName("Chebyshev omega parity — Java ref ↔ native across rho sweep")
    void testChebyshevOmegaCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping cross-backend parity");

        float[] rhos = {0.70f, 0.85f, 0.9245f, 0.95f, 0.98f, 0.995f};
        for (float rho : rhos) {
            for (int iter = 0; iter <= 80; iter++) {
                float ref    = PFSFScheduler.computeOmegaJavaRef(iter, rho);
                float native_ = NativePFSFBridge.nativeChebyshevOmega(iter, rho);
                assertEquals(ref, native_, 1e-5f,
                        "chebyshev omega parity: rho=" + rho + " iter=" + iter);
            }
        }
    }

    @Test
    @DisplayName("Precompute omega table parity across rho")
    void testPrecomputeOmegaTableCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        float[] rhos = {0.80f, 0.90f, 0.95f, 0.99f};
        for (float rho : rhos) {
            float[] javaTbl   = PFSFScheduler.precomputeOmegaTableJavaRef(rho);
            float[] nativeTbl = new float[javaTbl.length];
            int n = NativePFSFBridge.nativePrecomputeOmegaTable(rho, nativeTbl);
            assertEquals(javaTbl.length, n, "precompute table length mismatch");
            for (int i = 0; i < javaTbl.length; i++) {
                assertEquals(javaTbl[i], nativeTbl[i], 1e-5f,
                        "omega table[" + i + "] rho=" + rho);
            }
        }
    }

    @Test
    @DisplayName("Spectral radius parity — grid size sweep")
    void testSpectralRadiusCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        int[] Ls = {1, 2, 4, 8, 16, 32, 64, 128, 256};
        for (int L : Ls) {
            float ref    = PFSFScheduler.estimateSpectralRadiusJavaRef(L);
            float native_ = NativePFSFBridge.nativeEstimateSpectralRadius(L, PFSFConstants.SAFETY_MARGIN);
            assertEquals(ref, native_, 1e-5f, "spectral radius L=" + L);
        }
    }

    @Test
    @DisplayName("Recommend steps parity — dirty/collapse/height matrix")
    void testRecommendStepsCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        int[] lys = {4, 16, 32, 64, 96, 128, 200};
        int[] chebyIters = {0, 8, 32, 64, 65, 128};
        boolean[] flags = {false, true};
        for (int ly : lys) {
            for (int ci : chebyIters) {
                for (boolean dirty : flags) {
                    for (boolean collapse : flags) {
                        int ref = PFSFScheduler.recommendStepsJavaRef(ly, ci, dirty, collapse);
                        int nat = NativePFSFBridge.nativeRecommendSteps(ly, ci, dirty, collapse,
                                PFSFConstants.STEPS_MINOR,
                                PFSFConstants.STEPS_MAJOR,
                                PFSFConstants.STEPS_COLLAPSE);
                        assertEquals(ref, nat,
                                "recommendSteps ly=" + ly + " cheby=" + ci +
                                " dirty=" + dirty + " collapse=" + collapse);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Macro-block hysteresis parity — active/inactive boundaries")
    void testMacroBlockActiveCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        float[] residuals = {
                0.0f, 0.5e-4f, 0.8e-4f, 1.0e-4f, 1.2e-4f, 1.4e-4f,
                1.5e-4f, 1.6e-4f, 2.0e-4f, 1e-3f, Float.POSITIVE_INFINITY
        };
        for (float r : residuals) {
            for (boolean wasActive : new boolean[]{false, true}) {
                boolean ref = PFSFScheduler.isMacroBlockActiveJavaRef(r, wasActive);
                boolean nat = NativePFSFBridge.nativeMacroBlockActive(r, wasActive);
                assertEquals(ref, nat,
                        "macroBlockActive r=" + r + " wasActive=" + wasActive);
            }
        }
    }

    @Test
    @DisplayName("Macro active ratio parity — mixed residual array")
    void testMacroActiveRatioCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        SplittableRandom rng = new SplittableRandom(0x11223344L);
        int n = 64;
        float[] residuals = new float[n];
        boolean[] wasActive = new boolean[n];
        byte[] wasActiveBytes = new byte[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = (float) rng.nextDouble() * 3e-4f;
            wasActive[i] = (i & 1) == 0;
            wasActiveBytes[i] = wasActive[i] ? (byte) 1 : (byte) 0;
        }
        float ref = PFSFScheduler.getActiveRatioJavaRef(residuals, wasActive);
        float nat = NativePFSFBridge.nativeMacroActiveRatio(residuals, wasActiveBytes);
        assertEquals(ref, nat, 1e-6f, "active ratio");

        // wasActive = null branch
        float refNull = PFSFScheduler.getActiveRatioJavaRef(residuals, null);
        float natNull = NativePFSFBridge.nativeMacroActiveRatio(residuals, null);
        assertEquals(refNull, natNull, 1e-6f, "active ratio (null prev)");
    }

    @Test
    @DisplayName("Divergence guard parity — NaN triggers emergency reset")
    void testCheckDivergenceNanCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        int[] state = newDivergenceState(5.0f, 4.0f, 0, false, 50, 1.0e-5f);
        int kind = NativePFSFBridge.nativeCheckDivergence(state, Float.NaN, null,
                PFSFConstants.DIVERGENCE_RATIO, PFSFConstants.DAMPING_SETTLE_THRESHOLD);
        assertEquals(NativePFSFBridge.DivergenceKind.NAN_INF, kind);
        assertEquals(1, state[4], "damping_active must latch on NaN");
        assertEquals(0, state[5], "chebyshev_iter must reset on NaN");
        assertEquals(-1.0f, Float.intBitsToFloat(state[1]), 0.0f, "prev_max_phi becomes -1 sentinel");
    }

    @Test
    @DisplayName("Divergence guard parity — rapid growth crosses DIVERGENCE_RATIO")
    void testCheckDivergenceRapidGrowthCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        int[] state = newDivergenceState(1.0f, 0.9f, 0, false, 20, 0.0f);
        int kind = NativePFSFBridge.nativeCheckDivergence(state, 3.0f, null,
                PFSFConstants.DIVERGENCE_RATIO, PFSFConstants.DAMPING_SETTLE_THRESHOLD);
        assertEquals(NativePFSFBridge.DivergenceKind.RAPID_GROWTH, kind);
        assertEquals(0, state[5], "chebyshev_iter must reset");
        assertEquals(3.0f, Float.intBitsToFloat(state[1]), 0.0f);
        assertEquals(1.0f, Float.intBitsToFloat(state[2]), 0.0f);
    }

    @Test
    @DisplayName("Divergence guard parity — stable tick lands on NONE and advances history")
    void testCheckDivergenceStableCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        int[] state = newDivergenceState(2.0f, 2.0f, 0, true, 10, 0.0f);
        int kind = NativePFSFBridge.nativeCheckDivergence(state, 2.005f, null,
                PFSFConstants.DIVERGENCE_RATIO, PFSFConstants.DAMPING_SETTLE_THRESHOLD);
        assertEquals(NativePFSFBridge.DivergenceKind.NONE, kind);
        // Change ratio = |2.005 - 2.0| / 2.0 = 0.0025 < 0.01 → damping must settle off.
        assertEquals(0, state[4], "damping_active must settle when change < threshold");
        assertEquals(2.005f, Float.intBitsToFloat(state[1]), 0.0f);
        assertEquals(2.0f, Float.intBitsToFloat(state[2]), 0.0f);
    }

    @Test
    @DisplayName("Island feature vector parity — synthetic state reproduces 12 dims")
    void testExtractIslandFeaturesCrossParity() {
        assumeTrue(NativePFSFBridge.hasComputeV4(),
                "libpfsf_compute compute.v4 not available — skipping");

        SplittableRandom rng = new SplittableRandom(0x5EED5EEDL);
        float[] residuals = new float[32];
        for (int i = 0; i < residuals.length; i++) {
            residuals[i] = (float) rng.nextDouble() * 5e-4f;
        }

        float[] nat = new float[IslandFeatureExtractor.FEATURE_DIM];
        NativePFSFBridge.nativeExtractIslandFeatures(
                16, 24, 8,
                37, 0.93f, 1.2e-4f,
                2, true, 17,
                1, PFSFConstants.LOD_DORMANT,
                true,
                residuals, nat);

        // Build a fake buffer-less Java ref by expanding the same math
        // inline — IslandFeatureExtractor needs a PFSFIslandBuffer which
        // is heavyweight. Feature-by-feature comparison against the
        // baked formulas in @maps_to comments.
        int N = 16 * 24 * 8;
        int minDim = 8, maxDim = 24;
        float log2N = (float)(Math.log(N) / Math.log(2));
        float cv    = coefficientOfVariation(residuals);
        float cur   = maxOf(residuals);
        float prev  = 1.2e-4f;
        float drop  = (prev > 1e-20f) ? cur / prev : 1.0f;

        assertEquals(log2N,                              nat[0], 1e-4f,  "[0] log2(N)");
        assertEquals((float) maxDim / minDim,            nat[1], 1e-5f,  "[1] aspect");
        assertEquals(37.0f / 64.0f,                      nat[2], 1e-5f,  "[2] cheby_progress");
        assertEquals(0.93f,                              nat[3], 1e-5f,  "[3] rho");
        assertEquals((float) Math.log10(Math.max(prev, 1e-20f)), nat[4], 1e-4f, "[4] log10 residual");
        assertEquals(drop,                               nat[5], 1e-5f,  "[5] drop");
        assertEquals(Math.min(2 / 10.0f, 1.0f),          nat[6], 1e-5f,  "[6] oscillation");
        assertEquals(1.0f,                               nat[7], 1e-6f,  "[7] damping");
        assertEquals(Math.min(17 / 100.0f, 1.0f),        nat[8], 1e-5f,  "[8] stability");
        assertEquals(cv,                                 nat[9], 1e-5f,  "[9] cv");
        assertEquals(1.0f / Math.max(PFSFConstants.LOD_DORMANT, 1),
                                                         nat[10], 1e-5f, "[10] lod");
        assertEquals(1.0f,                               nat[11], 1e-6f, "[11] pcg");
    }

    // ── helpers for Phase 4 diagnostics tests ───────────────────────────

    private static int[] newDivergenceState(float prev, float prevPrev,
                                              int oscillationCount, boolean dampingActive,
                                              int chebyshevIter, float prevMacroRes) {
        int[] s = new int[7];
        s[0] = 28;
        s[1] = Float.floatToRawIntBits(prev);
        s[2] = Float.floatToRawIntBits(prevPrev);
        s[3] = oscillationCount;
        s[4] = dampingActive ? 1 : 0;
        s[5] = chebyshevIter;
        s[6] = Float.floatToRawIntBits(prevMacroRes);
        return s;
    }

    private static float maxOf(float[] arr) {
        if (arr == null || arr.length == 0) return 0.0f;
        float m = arr[0];
        for (int i = 1; i < arr.length; i++) if (arr[i] > m) m = arr[i];
        return m;
    }

    private static float coefficientOfVariation(float[] arr) {
        if (arr == null || arr.length < 2) return 0.0f;
        double sum = 0.0, sum2 = 0.0;
        int n = arr.length;
        for (float v : arr) { sum += v; sum2 += (double) v * v; }
        double mean = sum / n;
        if (mean < 1e-20) return 0.0f;
        double variance = sum2 / n - mean * mean;
        return (float) (Math.sqrt(Math.max(variance, 0.0)) / mean);
    }

    // ── Phase 5 — extension SPI registry round-trip (compute.v5) ────────

    @Test
    @DisplayName("Augmentation slot round-trip — register / query / clear")
    void testAugmentationRoundTrip() {
        assumeTrue(NativePFSFBridge.hasComputeV5(),
                "libpfsf_compute compute.v5 not available — skipping");

        int islandId = 424242;
        NativePFSFBridge.nativeAugClearIsland(islandId);       // clean slate

        ByteBuffer dbb = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
        boolean ok = PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD, dbb, 4, 7);
        assertTrue(ok, "register should accept a direct buffer");

        assertEquals(1, PFSFAugmentationHost.islandCount(islandId));
        assertEquals(7, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertEquals(-1, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE),
                "unregistered kind must report -1");

        int[] out = new int[4];
        boolean found = NativePFSFBridge.nativeAugQuery(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD, out);
        assertTrue(found);
        assertEquals(NativePFSFBridge.AugKind.THERMAL_FIELD, out[0]);
        assertEquals(4,    out[1], "stride parity");
        assertEquals(7,    out[2], "version parity");
        assertEquals(1024, out[3], "dbb bytes parity");

        PFSFAugmentationHost.clear(islandId, NativePFSFBridge.AugKind.THERMAL_FIELD);
        assertEquals(-1, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertEquals(0, PFSFAugmentationHost.islandCount(islandId));
    }

    @Test
    @DisplayName("Multiple slots per island — independent lifecycle")
    void testAugmentationMultiSlot() {
        assumeTrue(NativePFSFBridge.hasComputeV5(),
                "libpfsf_compute compute.v5 not available — skipping");

        int islandId = 424243;
        NativePFSFBridge.nativeAugClearIsland(islandId);

        ByteBuffer thermal = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        ByteBuffer fluid   = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        ByteBuffer curing  = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());

        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD, thermal, 4, 1));
        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE, fluid, 4, 2));
        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.CURING_FIELD, curing, 4, 3));

        assertEquals(3, PFSFAugmentationHost.islandCount(islandId));
        assertEquals(1, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertEquals(2, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE));
        assertEquals(3, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.CURING_FIELD));

        // Overwrite one slot — count must stay at 3 but version updates.
        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE, fluid, 4, 42));
        assertEquals(3, PFSFAugmentationHost.islandCount(islandId));
        assertEquals(42, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE));

        // clearIsland nukes all three.
        PFSFAugmentationHost.clearIsland(islandId);
        assertEquals(0, PFSFAugmentationHost.islandCount(islandId));
    }

    // ── Phase 6 — plan buffer opcode dispatcher (compute.v6) ────────────

    @Test
    @DisplayName("Plan buffer: NO_OP + INCR_COUNTER preserves order and arity")
    void testPlanIncrCounter() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");
        // Drain any stray state from earlier tests.
        NativePFSFBridge.nativePlanTestCounterReadReset();

        PFSFTickPlanner plan = PFSFTickPlanner.forIsland(0x1001)
                .pushNoOp()
                .pushIncrCounter(3)
                .pushIncrCounter(5)
                .pushNoOp()
                .pushIncrCounter(-1);

        int[] res = new int[4];
        int code = plan.execute(res);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code, "plan should execute cleanly");
        assertEquals(5, res[0], "all 5 opcodes must execute");
        assertEquals(-1, res[1], "no failure index");
        assertEquals(0, res[2], "no error");
        assertEquals(0, res[3], "no hooks fired");

        long counter = NativePFSFBridge.nativePlanTestCounterReadReset();
        assertEquals(7L, counter, "counter = 3 + 5 + (-1)");
    }

    @Test
    @DisplayName("Plan buffer: CLEAR_AUG opcode drops registered slots")
    void testPlanClearAug() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");
        assumeTrue(NativePFSFBridge.hasComputeV5(),
                "need compute.v5 for augmentation registry");

        int islandId = 0x1002;
        NativePFSFBridge.nativeAugClearIsland(islandId);

        ByteBuffer thermal = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        ByteBuffer fluid   = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD, thermal, 4, 1));
        assertTrue(PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE, fluid, 4, 1));
        assertEquals(2, PFSFAugmentationHost.islandCount(islandId));

        // Clear just thermal via the plan buffer.
        int[] res = new int[4];
        int code = PFSFTickPlanner.forIsland(islandId)
                .pushClearAug(NativePFSFBridge.AugKind.THERMAL_FIELD)
                .execute(res);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertEquals(1, res[0]);

        assertEquals(-1, PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertEquals(1,  PFSFAugmentationHost.queryVersion(islandId,
                NativePFSFBridge.AugKind.FLUID_PRESSURE));

        // Clear the rest via CLEAR_AUG_ISLAND.
        PFSFTickPlanner.forIsland(islandId).pushClearAugIsland().execute();
        assertEquals(0, PFSFAugmentationHost.islandCount(islandId));
    }

    @Test
    @DisplayName("Plan buffer: FIRE_HOOK drives installed test hook")
    void testPlanFireHook() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");

        int islandId = 0x1003;
        int point    = NativePFSFBridge.HookPoint.POST_SOURCE;
        NativePFSFBridge.nativeHookClearIsland(islandId);
        NativePFSFBridge.nativePlanTestHookInstall(islandId, point);

        int[] res = new int[4];
        int code = PFSFTickPlanner.forIsland(islandId)
                .pushFireHook(point, 1L)
                .pushFireHook(point, 2L)
                .pushFireHook(point, 3L)
                .execute(res);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertEquals(3, res[0], "executed three opcodes");
        assertEquals(3, res[3], "three hook fires");

        long fires = NativePFSFBridge.nativePlanTestHookCountReadReset(islandId, point);
        assertEquals(3L, fires);

        // A fire on a point with no hook must be a silent no-op.
        int other = NativePFSFBridge.HookPoint.PRE_SCAN;
        int[] res2 = new int[4];
        PFSFTickPlanner.forIsland(islandId)
                .pushFireHook(other, 99L)
                .execute(res2);
        assertEquals(0, res2[3], "unwired hook must not count");
        NativePFSFBridge.nativeHookClearIsland(islandId);
    }

    @Test
    @DisplayName("Plan buffer: malformed magic / truncated body surface as INVALID_ARG")
    void testPlanMalformedRejected() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");

        ByteBuffer bad = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        bad.putInt(0xDEADBEEF);          // bad magic
        bad.putShort((short) 1);
        bad.putShort((short) 0);
        bad.putInt(0);
        bad.putInt(0);

        int[] res = new int[4];
        int code = NativePFSFBridge.nativePlanExecute(bad, 16, res);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, code);
        assertEquals(0, res[0]);

        // Truncated body: header claims 3 opcodes but buffer is only 18 bytes long
        ByteBuffer trunc = ByteBuffer.allocateDirect(18).order(ByteOrder.LITTLE_ENDIAN);
        trunc.putInt(0x46534650);
        trunc.putShort((short) 1);
        trunc.putShort((short) 0);
        trunc.putInt(9001);
        trunc.putInt(3);                 // opcode_count=3 but only room for 0
        trunc.putShort((short) 0);       // partial opcode
        int[] res2 = new int[4];
        int code2 = NativePFSFBridge.nativePlanExecute(trunc, 18, res2);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, code2);
        assertEquals(0, res2[0]);
        assertEquals(0, res2[1], "failed at opcode index 0");
    }

    @Test
    @DisplayName("Plan buffer: unknown opcode stops dispatch at failure index")
    void testPlanUnknownOpcode() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");
        NativePFSFBridge.nativePlanTestCounterReadReset();

        // Build: NO_OP, INCR_COUNTER(+4), UNKNOWN(0xFFFF), INCR_COUNTER(+100)
        ByteBuffer plan = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        plan.putInt(0x46534650);
        plan.putShort((short) 1);
        plan.putShort((short) 0);
        plan.putInt(0xBEEF);
        plan.putInt(4);                  // 4 opcodes
        // op 0: NO_OP
        plan.putShort((short) 0); plan.putShort((short) 0);
        // op 1: INCR_COUNTER(4)
        plan.putShort((short) 1); plan.putShort((short) 4); plan.putInt(4);
        // op 2: opcode 0xFFFF (unknown)
        plan.putShort((short) 0xFFFF); plan.putShort((short) 0);
        // op 3: INCR_COUNTER(100) — must NOT run
        plan.putShort((short) 1); plan.putShort((short) 4); plan.putInt(100);

        int bytesUsed = plan.position();
        int[] res = new int[4];
        int code = NativePFSFBridge.nativePlanExecute(plan, bytesUsed, res);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, code);
        assertEquals(2, res[0], "first two ops executed before the bad one");
        assertEquals(2, res[1], "failed at index 2");

        long counter = NativePFSFBridge.nativePlanTestCounterReadReset();
        assertEquals(4L, counter, "only the first INCR_COUNTER ran");
    }

    @Test
    @DisplayName("PFSFTickPlanner grows buffer past initial reserve")
    void testPlannerGrows() {
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "libpfsf_compute compute.v6 not available — skipping");
        NativePFSFBridge.nativePlanTestCounterReadReset();

        PFSFTickPlanner plan = PFSFTickPlanner.forIsland(0x1004, 24);
        for (int i = 0; i < 100; i++) plan.pushIncrCounter(1);
        assertEquals(100, plan.opCount());

        int[] res = new int[4];
        assertEquals(NativePFSFBridge.PFSFResult.OK, plan.execute(res));
        assertEquals(100, res[0]);
        assertEquals(100L, NativePFSFBridge.nativePlanTestCounterReadReset());
    }

    // ── Phase 7 — trace ring buffer (compute.v7) ────────────────────────

    @Test
    @DisplayName("Trace ring: emit + drain round-trip preserves fields")
    void testTraceRingRoundTrip() {
        assumeTrue(NativePFSFBridge.hasComputeV7(),
                "libpfsf_compute compute.v7 not available — skipping");
        PFSFTrace.clear();
        PFSFTrace.setLevel(NativePFSFBridge.TraceLevel.VERBOSE);

        PFSFTrace.emit(NativePFSFBridge.TraceLevel.ERROR,
                42L, 2, 777, 88, -4, "hello trace");
        PFSFTrace.emit(NativePFSFBridge.TraceLevel.WARN,
                43L, 3, 777, -1, 0, "second event");

        assertEquals(2, PFSFTrace.size());
        List<PFSFTrace.Event> events = PFSFTrace.drain(16);
        assertEquals(2, events.size());

        PFSFTrace.Event a = events.get(0);
        assertEquals(42L, a.epoch);
        assertEquals(2,   a.stage);
        assertEquals(777, a.islandId);
        assertEquals(88,  a.voxelIndex);
        assertEquals(-4,  a.errnoVal);
        assertEquals((short) NativePFSFBridge.TraceLevel.ERROR, a.level);
        assertEquals("hello trace", a.msg);

        PFSFTrace.Event b = events.get(1);
        assertEquals(43L, b.epoch);
        assertEquals("second event", b.msg);

        assertEquals(0, PFSFTrace.size(), "drain must empty the ring");
    }

    @Test
    @DisplayName("Trace ring: level threshold drops events below threshold")
    void testTraceLevelFiltering() {
        assumeTrue(NativePFSFBridge.hasComputeV7(),
                "libpfsf_compute compute.v7 not available — skipping");
        PFSFTrace.clear();
        PFSFTrace.setLevel(NativePFSFBridge.TraceLevel.WARN);

        PFSFTrace.emit(NativePFSFBridge.TraceLevel.ERROR,   1L, 0, 1, -1, 0, "error");
        PFSFTrace.emit(NativePFSFBridge.TraceLevel.WARN,    2L, 0, 1, -1, 0, "warn");
        PFSFTrace.emit(NativePFSFBridge.TraceLevel.INFO,    3L, 0, 1, -1, 0, "info");
        PFSFTrace.emit(NativePFSFBridge.TraceLevel.VERBOSE, 4L, 0, 1, -1, 0, "verbose");

        assertEquals(2, PFSFTrace.size(), "only ERROR + WARN survive at level=WARN");

        PFSFTrace.setLevel(NativePFSFBridge.TraceLevel.OFF);
        PFSFTrace.emit(NativePFSFBridge.TraceLevel.ERROR,  5L, 0, 1, -1, 0, "even error");
        assertEquals(2, PFSFTrace.size(), "OFF must drop everything");
        PFSFTrace.clear();
    }

    @Test
    @DisplayName("Trace ring: plan dispatcher emits on malformed header")
    void testTracePlanErrorIntegration() {
        assumeTrue(NativePFSFBridge.hasComputeV7(),
                "libpfsf_compute compute.v7 not available — skipping");
        assumeTrue(NativePFSFBridge.hasComputeV6(),
                "need compute.v6 for plan dispatcher");
        PFSFTrace.clear();
        PFSFTrace.setLevel(NativePFSFBridge.TraceLevel.ERROR);

        ByteBuffer bad = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        bad.putInt(0xDEADBEEF);          // bad magic
        bad.putShort((short) 1); bad.putShort((short) 0);
        bad.putInt(123);                 // island_id
        bad.putInt(0);
        int code = NativePFSFBridge.nativePlanExecute(bad, 16, new int[4]);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, code);

        List<PFSFTrace.Event> drained = PFSFTrace.drain(4);
        assertFalse(drained.isEmpty(), "dispatcher should emit on bad header");
        PFSFTrace.Event e = drained.get(0);
        assertEquals(123, e.islandId);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, e.errnoVal);
        assertTrue(e.msg.contains("plan"), "msg should describe plan error");
    }

    @Test
    @DisplayName("Trace ring: drop-oldest when capacity is exceeded")
    void testTraceDropOldest() {
        assumeTrue(NativePFSFBridge.hasComputeV7(),
                "libpfsf_compute compute.v7 not available — skipping");
        PFSFTrace.clear();
        PFSFTrace.setLevel(NativePFSFBridge.TraceLevel.VERBOSE);

        // Capacity in the ring is 4096. Emit 4100 — first 4 should roll off.
        final int cap = 4096;
        for (int i = 0; i < cap + 4; i++) {
            PFSFTrace.emit(NativePFSFBridge.TraceLevel.INFO,
                    i, 0, 0, -1, 0, "e" + i);
        }
        assertEquals(cap, PFSFTrace.size(), "ring must saturate at capacity");

        List<PFSFTrace.Event> first = PFSFTrace.drain(1);
        assertEquals(1, first.size());
        assertEquals(4L, first.get(0).epoch,
                "oldest surviving event should be epoch=4 (0..3 rolled off)");
        PFSFTrace.clear();
    }

    @Test
    @DisplayName("Augmentation register rejects non-direct / null buffers")
    void testAugmentationRejectsIndirect() {
        assumeTrue(NativePFSFBridge.hasComputeV5(),
                "libpfsf_compute compute.v5 not available — skipping");

        int islandId = 424244;
        NativePFSFBridge.nativeAugClearIsland(islandId);

        boolean okNull = PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.WIND_FIELD_3D, null, 12, 1);
        assertFalse(okNull, "null buffer must be rejected");

        ByteBuffer heap = ByteBuffer.allocate(128);  // non-direct
        boolean okHeap = PFSFAugmentationHost.register(islandId,
                NativePFSFBridge.AugKind.WIND_FIELD_3D, heap, 12, 1);
        assertFalse(okHeap, "heap-allocated buffer must be rejected");

        assertEquals(0, PFSFAugmentationHost.islandCount(islandId));
    }

    // ── v0.3e M2 — plan buffer compute opcode parity (compute.v8) ───────
    //
    // Each test drives the new plan-buffer dispatcher with one compute
    // opcode, then compares the result to the existing per-primitive
    // JNI path that is already parity-tested against the Java reference
    // above. The goal is to prove the dispatch layer itself is wiring
    // arg layouts correctly — any numeric drift between "direct JNI" and
    // "through plan" means the record layout (arg offsets, endianness,
    // address slot widths) diverged between pfsf_plan.h, the dispatcher,
    // and PFSFTickPlanner. Errors in the underlying kernel would already
    // have been caught by the Phase 1-4 cross-parity tests.

    /** Allocate a little-endian direct buffer sized for {@code n} floats. */
    private static ByteBuffer floatDbb(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
    }
    /** Allocate a little-endian direct buffer sized for {@code n} ints. */
    private static ByteBuffer intDbb(int n) { return floatDbb(n); }
    /** Allocate a little-endian direct buffer sized for {@code n} bytes. */
    private static ByteBuffer byteDbb(int n) {
        return ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer putFloats(ByteBuffer dbb, float[] src) {
        dbb.position(0);
        for (float f : src) dbb.putFloat(f);
        dbb.position(0);
        return dbb;
    }
    private static ByteBuffer putBytes(ByteBuffer dbb, byte[] src) {
        dbb.position(0);
        dbb.put(src);
        dbb.position(0);
        return dbb;
    }
    private static float[] readFloats(ByteBuffer dbb, int n) {
        float[] out = new float[n];
        dbb.position(0);
        for (int i = 0; i < n; i++) out[i] = dbb.getFloat();
        return out;
    }
    private static int[] readInts(ByteBuffer dbb, int n) {
        int[] out = new int[n];
        dbb.position(0);
        for (int i = 0; i < n; i++) out[i] = dbb.getInt();
        return out;
    }
    private static byte[] readBytes(ByteBuffer dbb, int n) {
        byte[] out = new byte[n];
        dbb.position(0);
        dbb.get(out);
        return out;
    }

    @Test
    @DisplayName("Plan NORMALIZE_SOA6 matches direct JNI (compute.v8)")
    void testPlanNormalizeSoA6() {
        assumeTrue(NativePFSFBridge.hasComputeV8(),
                "compute.v8 unavailable — plan compute opcodes skipped.");
        final int N = 128;
        SplittableRandom rng = new SplittableRandom(0xC0FFEE01L);
        float[] src  = new float[N], rc = new float[N], rt = new float[N];
        float[] cond = new float[6 * N];
        for (int i = 0; i < N; i++) {
            src[i] = (float)(rng.nextDouble() * 20 - 10);
            rc[i]  = (float)(rng.nextDouble() * 100);
            rt[i]  = (float)(rng.nextDouble() * 20);
        }
        for (int i = 0; i < cond.length; i++) cond[i] = (float)(rng.nextDouble() * 50);
        cond[5] = 42.0f;  // ensure branch fires

        // Direct JNI reference.
        float[] srcR = src.clone(), rcR = rc.clone(), rtR = rt.clone(), cR = cond.clone();
        float sigmaR = NativePFSFBridge.nativeNormalizeSoA6(srcR, rcR, rtR, cR, null, N);

        // Plan-buffer path.
        ByteBuffer bSrc  = putFloats(floatDbb(N), src);
        ByteBuffer bRc   = putFloats(floatDbb(N), rc);
        ByteBuffer bRt   = putFloats(floatDbb(N), rt);
        ByteBuffer bCond = putFloats(floatDbb(6 * N), cond);
        ByteBuffer bSig  = floatDbb(1);

        int[] res = new int[4];
        int code = PFSFTickPlanner.forIsland(7001)
                .pushNormalizeSoA6(
                        NativePFSFBridge.nativeDirectBufferAddress(bSrc),
                        NativePFSFBridge.nativeDirectBufferAddress(bRc),
                        NativePFSFBridge.nativeDirectBufferAddress(bRt),
                        NativePFSFBridge.nativeDirectBufferAddress(bCond),
                        0L,                                   // no hydration
                        NativePFSFBridge.nativeDirectBufferAddress(bSig),
                        N)
                .execute(res);
        assertEquals(NativePFSFBridge.PFSFResult.OK, code, "plan normalize should succeed");

        assertEquals(sigmaR, readFloats(bSig, 1)[0], STRESS_ABS_TOL, "sigmaMax drift");
        assertArrayEquals(srcR, readFloats(bSrc, N),     STRESS_ABS_TOL);
        assertArrayEquals(rcR,  readFloats(bRc,  N),     STRESS_ABS_TOL);
        assertArrayEquals(rtR,  readFloats(bRt,  N),     STRESS_ABS_TOL);
        assertArrayEquals(cR,   readFloats(bCond, 6 * N), STRESS_ABS_TOL);
    }

    @Test
    @DisplayName("Plan APPLY_WIND_BIAS matches direct JNI (compute.v8)")
    void testPlanApplyWindBias() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int N = 64;
        float[] base = new float[6 * N];
        SplittableRandom rng = new SplittableRandom(0xC0FFEE02L);
        for (int i = 0; i < base.length; i++) base[i] = (float)(rng.nextDouble() + 0.1);

        float[] ref = base.clone();
        NativePFSFBridge.nativeApplyWindBias(ref, N, 1.0f, 0.0f, 0.5f, 0.3f);

        ByteBuffer bCond = putFloats(floatDbb(6 * N), base);
        int code = PFSFTickPlanner.forIsland(7002)
                .pushApplyWindBias(NativePFSFBridge.nativeDirectBufferAddress(bCond),
                        N, 1.0f, 0.0f, 0.5f, 0.3f)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(ref, readFloats(bCond, 6 * N), STRESS_ABS_TOL);
    }

    @Test
    @DisplayName("Plan COMPUTE_CONDUCTIVITY fills SoA-6 deterministically (compute.v8)")
    void testPlanComputeConductivity() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        // 3×3×3 cube, all solid, interior rcomp=20, rtens=5.
        final int lx = 3, ly = 3, lz = 3, N = lx * ly * lz;
        byte[] type = new byte[N];
        float[] rc  = new float[N];
        float[] rt  = new float[N];
        for (int i = 0; i < N; i++) { type[i] = 1; rc[i] = 20.0f; rt[i] = 5.0f; }
        float[] cond = new float[6 * N];  // zeroed output

        ByteBuffer bCond = putFloats(floatDbb(6 * N), cond);
        ByteBuffer bRc   = putFloats(floatDbb(N), rc);
        ByteBuffer bRt   = putFloats(floatDbb(N), rt);
        ByteBuffer bType = putBytes(byteDbb(N), type);

        int code = PFSFTickPlanner.forIsland(7003)
                .pushComputeConductivity(
                        NativePFSFBridge.nativeDirectBufferAddress(bCond),
                        NativePFSFBridge.nativeDirectBufferAddress(bRc),
                        NativePFSFBridge.nativeDirectBufferAddress(bRt),
                        NativePFSFBridge.nativeDirectBufferAddress(bType),
                        lx, ly, lz,
                        0.0f, 0.0f, 0.0f, 0.0f)  // no wind
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);

        float[] out = readFloats(bCond, 6 * N);
        // Interior voxel (1,1,1) — index 13 — all 6 neighbours are solid.
        final int i = 1 + lx * (1 + ly * 1);
        // Vertical faces (d=2,3) use base=min(rcomp)=20.
        assertEquals(20.0f, out[2 * N + i], STRESS_ABS_TOL, "-Y conductivity");
        assertEquals(20.0f, out[3 * N + i], STRESS_ABS_TOL, "+Y conductivity");
        // Horizontal faces apply tension ratio: avgRtens=5, base=20 ⇒ ratio=0.25.
        final float horiz = 20.0f * 0.25f;
        assertEquals(horiz, out[0 * N + i], STRESS_ABS_TOL, "-X conductivity");
        assertEquals(horiz, out[1 * N + i], STRESS_ABS_TOL, "+X conductivity");
        assertEquals(horiz, out[4 * N + i], STRESS_ABS_TOL, "-Z conductivity");
        assertEquals(horiz, out[5 * N + i], STRESS_ABS_TOL, "+Z conductivity");

        // Corner voxel (0,0,0) — faces pointing into the -X/-Y/-Z walls
        // must be exactly zero because the neighbour lies out of bounds.
        final int c = 0;
        assertEquals(0.0f, out[0 * N + c], 0.0f, "-X out-of-bounds must be 0");
        assertEquals(0.0f, out[2 * N + c], 0.0f, "-Y out-of-bounds must be 0");
        assertEquals(0.0f, out[4 * N + c], 0.0f, "-Z out-of-bounds must be 0");
    }

    @Test
    @DisplayName("Plan ARM_MAP matches direct JNI (compute.v8)")
    void testPlanArmMap() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lx = 5, ly = 3, lz = 2, N = lx * ly * lz;
        byte[] members = new byte[N], anchors = new byte[N];
        for (int i = 0; i < N; i++) members[i] = 1;
        for (int z = 0; z < lz; z++) anchors[lx * ly * z] = 1;

        int[] ref = new int[N];
        int rc = NativePFSFBridge.nativeComputeArmMap(members, anchors, lx, ly, lz, ref);
        assertEquals(NativePFSFBridge.PFSFResult.OK, rc);

        ByteBuffer bM = putBytes(byteDbb(N), members);
        ByteBuffer bA = putBytes(byteDbb(N), anchors);
        ByteBuffer bO = intDbb(N);
        int code = PFSFTickPlanner.forIsland(7004)
                .pushArmMap(NativePFSFBridge.nativeDirectBufferAddress(bM),
                            NativePFSFBridge.nativeDirectBufferAddress(bA),
                            NativePFSFBridge.nativeDirectBufferAddress(bO),
                            lx, ly, lz)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(ref, readInts(bO, N));
    }

    @Test
    @DisplayName("Plan ARCH_FACTOR matches direct JNI (compute.v8)")
    void testPlanArchFactor() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lx = 5, ly = 2, lz = 2, N = lx * ly * lz;
        byte[] members = new byte[N], anchors = new byte[N];
        for (int i = 0; i < N; i++) members[i] = 1;
        // Two anchor groups at the two x-extremes.
        for (int z = 0; z < lz; z++) {
            anchors[lx * ly * z + 0] = 1;
            anchors[lx * ly * z + (lx - 1)] = 1;
        }

        float[] ref = new float[N];
        int rc = NativePFSFBridge.nativeComputeArchFactorMap(members, anchors, lx, ly, lz, ref);
        assertEquals(NativePFSFBridge.PFSFResult.OK, rc);

        ByteBuffer bM = putBytes(byteDbb(N), members);
        ByteBuffer bA = putBytes(byteDbb(N), anchors);
        ByteBuffer bO = floatDbb(N);
        int code = PFSFTickPlanner.forIsland(7005)
                .pushArchFactor(NativePFSFBridge.nativeDirectBufferAddress(bM),
                                NativePFSFBridge.nativeDirectBufferAddress(bA),
                                NativePFSFBridge.nativeDirectBufferAddress(bO),
                                lx, ly, lz)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(ref, readFloats(bO, N), STRESS_ABS_TOL);
    }

    @Test
    @DisplayName("Plan PHANTOM_EDGES matches direct JNI and writes injected count (compute.v8)")
    void testPlanPhantomEdges() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lx = 3, ly = 3, lz = 3, N = lx * ly * lz;
        byte[] members = new byte[N];
        float[] cond0  = new float[6 * N];
        float[] rc     = new float[N];
        for (int i = 0; i < N; i++) { members[i] = 1; rc[i] = 10.0f; }
        for (int i = 0; i < cond0.length; i++) cond0[i] = 5.0f;

        float[] condRef = cond0.clone();
        int injRef = NativePFSFBridge.nativeInjectPhantomEdges(members, condRef, rc,
                lx, ly, lz, 0.35f, 0.15f);

        ByteBuffer bM    = putBytes(byteDbb(N), members);
        ByteBuffer bCond = putFloats(floatDbb(6 * N), cond0);
        ByteBuffer bRc   = putFloats(floatDbb(N), rc);
        ByteBuffer bInj  = intDbb(1);
        int code = PFSFTickPlanner.forIsland(7006)
                .pushPhantomEdges(NativePFSFBridge.nativeDirectBufferAddress(bM),
                                  NativePFSFBridge.nativeDirectBufferAddress(bCond),
                                  NativePFSFBridge.nativeDirectBufferAddress(bRc),
                                  NativePFSFBridge.nativeDirectBufferAddress(bInj),
                                  lx, ly, lz, 0.35f, 0.15f)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(condRef, readFloats(bCond, 6 * N), STRESS_ABS_TOL);
        assertEquals(injRef, readInts(bInj, 1)[0], "injected count drift");
    }

    @Test
    @DisplayName("Plan DOWNSAMPLE_2TO1 matches direct JNI (compute.v8)")
    void testPlanDownsample2to1() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lxf = 4, lyf = 4, lzf = 4, Nf = lxf * lyf * lzf;
        final int lxc = 2, lyc = 2, lzc = 2, Nc = lxc * lyc * lzc;
        float[] fine = new float[Nf];
        byte[] fineType = new byte[Nf];
        SplittableRandom rng = new SplittableRandom(0xC0FFEE03L);
        for (int i = 0; i < Nf; i++) {
            fine[i] = (float)(rng.nextDouble() * 10);
            fineType[i] = (byte)(rng.nextInt(3));
        }
        float[] coarseR = new float[Nc];
        byte[] coarseTR = new byte[Nc];
        int rc = NativePFSFBridge.nativeDownsample2to1(fine, fineType, lxf, lyf, lzf,
                coarseR, coarseTR);
        assertEquals(NativePFSFBridge.PFSFResult.OK, rc);

        ByteBuffer bF  = putFloats(floatDbb(Nf), fine);
        ByteBuffer bFT = putBytes(byteDbb(Nf), fineType);
        ByteBuffer bC  = floatDbb(Nc);
        ByteBuffer bCT = byteDbb(Nc);
        int code = PFSFTickPlanner.forIsland(7007)
                .pushDownsample2to1(NativePFSFBridge.nativeDirectBufferAddress(bF),
                                    NativePFSFBridge.nativeDirectBufferAddress(bFT),
                                    NativePFSFBridge.nativeDirectBufferAddress(bC),
                                    NativePFSFBridge.nativeDirectBufferAddress(bCT),
                                    lxf, lyf, lzf)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(coarseR,  readFloats(bC,  Nc), STRESS_ABS_TOL);
        assertArrayEquals(coarseTR, readBytes(bCT, Nc));
    }

    @Test
    @DisplayName("Plan TILED_LAYOUT matches direct JNI (compute.v8)")
    void testPlanTiledLayout() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lx = 8, ly = 8, lz = 8, N = lx * ly * lz;
        final int tile = 8;
        float[] linear = new float[N];
        for (int i = 0; i < N; i++) linear[i] = i;

        // Tiled output size: ntx*nty*ntz*tile^3. For a full 8^3 that's 512.
        float[] ref = new float[N];
        int rc = NativePFSFBridge.nativeTiledLayoutBuild(linear, lx, ly, lz, tile, ref);
        assertEquals(NativePFSFBridge.PFSFResult.OK, rc);

        ByteBuffer bIn  = putFloats(floatDbb(N), linear);
        ByteBuffer bOut = floatDbb(N);
        int code = PFSFTickPlanner.forIsland(7008)
                .pushTiledLayout(NativePFSFBridge.nativeDirectBufferAddress(bIn),
                                 NativePFSFBridge.nativeDirectBufferAddress(bOut),
                                 lx, ly, lz, tile)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(ref, readFloats(bOut, N), STRESS_ABS_TOL);
    }

    @Test
    @DisplayName("Plan CHEBYSHEV writes omega matching direct JNI (compute.v8)")
    void testPlanChebyshev() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        for (int iter = 0; iter < 5; iter++) {
            final float rho = 0.95f;
            float ref = NativePFSFBridge.nativeChebyshevOmega(iter, rho);

            ByteBuffer bOut = floatDbb(1);
            int code = PFSFTickPlanner.forIsland(7009)
                    .pushChebyshev(NativePFSFBridge.nativeDirectBufferAddress(bOut),
                                   iter, rho)
                    .execute();
            assertEquals(NativePFSFBridge.PFSFResult.OK, code, "iter=" + iter);
            assertEquals(ref, readFloats(bOut, 1)[0], STRESS_ABS_TOL,
                    "chebyshev drift @ iter=" + iter);
        }
    }

    @Test
    @DisplayName("Plan CHECK_DIVERGENCE mutates state and writes kind (compute.v8)")
    void testPlanCheckDivergence() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        // NaN path — deterministic regardless of history.
        int[] refState = newDivergenceState(1.0f, 1.0f, 0, false, 5, 1e-3f);
        int kindRef = NativePFSFBridge.nativeCheckDivergence(refState, Float.NaN, null,
                10.0f, 1e-5f);

        // Mirror the state layout into a DBB for the plan path. The
        // native contract treats the first int32 as struct_bytes — the
        // int[7] helper already sets that, so copy verbatim.
        ByteBuffer bSt = ByteBuffer.allocateDirect(7 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : newDivergenceState(1.0f, 1.0f, 0, false, 5, 1e-3f)) bSt.putInt(v);
        bSt.position(0);
        ByteBuffer bKind = intDbb(1);

        int code = PFSFTickPlanner.forIsland(7010)
                .pushCheckDivergence(NativePFSFBridge.nativeDirectBufferAddress(bSt),
                                     0L, // no macro residuals
                                     NativePFSFBridge.nativeDirectBufferAddress(bKind),
                                     Float.NaN, 0, 10.0f, 1e-5f)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertEquals(kindRef, readInts(bKind, 1)[0], "divergence kind drift");
        // State mutation matches the direct-JNI path byte for byte.
        int[] planState = readInts(bSt, 7);
        assertArrayEquals(refState, planState, "divergence state drift");
    }

    @Test
    @DisplayName("Plan EXTRACT_FEATURES matches direct JNI (compute.v8)")
    void testPlanExtractFeatures() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final int lx = 4, ly = 5, lz = 6;
        final int macroCount = 8;
        float[] macro = new float[macroCount];
        for (int i = 0; i < macroCount; i++) macro[i] = 1e-4f * (i + 1);

        float[] ref = new float[12];
        NativePFSFBridge.nativeExtractIslandFeatures(lx, ly, lz, 7, 0.95f, 1e-3f,
                2, true, 30, 1, 4, false, macro, ref);

        ByteBuffer bRes = putFloats(floatDbb(macroCount), macro);
        ByteBuffer bOut = floatDbb(12);
        int code = PFSFTickPlanner.forIsland(7011)
                .pushExtractFeatures(NativePFSFBridge.nativeDirectBufferAddress(bRes),
                                     NativePFSFBridge.nativeDirectBufferAddress(bOut),
                                     lx, ly, lz,
                                     /*chebyshevIter*/ 7,
                                     /*osc*/ 2, /*damping*/ 1,
                                     /*stable*/ 30, /*lod*/ 1, /*dormant*/ 4,
                                     /*pcgAllocated*/ 0, macroCount,
                                     0.95f, 1e-3f)
                .execute();
        assertEquals(NativePFSFBridge.PFSFResult.OK, code);
        assertArrayEquals(ref, readFloats(bOut, 12), STRESS_ABS_TOL);
    }

    @Test
    @DisplayName("Plan WIND_PRESSURE writes matching float (compute.v8)")
    void testPlanWindPressure() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final float speed = 22.5f, density = 1.225f;
        for (boolean exposed : new boolean[]{true, false}) {
            float ref = NativePFSFBridge.nativeWindPressureSource(speed, density, exposed);
            ByteBuffer bOut = floatDbb(1);
            int code = PFSFTickPlanner.forIsland(7012)
                    .pushWindPressure(NativePFSFBridge.nativeDirectBufferAddress(bOut),
                                      speed, density, exposed)
                    .execute();
            assertEquals(NativePFSFBridge.PFSFResult.OK, code);
            assertEquals(ref, readFloats(bOut, 1)[0], STRESS_ABS_TOL,
                    "wind pressure drift exposed=" + exposed);
        }
    }

    @Test
    @DisplayName("Plan TIMOSHENKO writes matching moment factor (compute.v8)")
    void testPlanTimoshenko() {
        assumeTrue(NativePFSFBridge.hasComputeV8(), "compute.v8 unavailable");
        final float b = 0.3f, h = 0.5f, youngs = 30.0f, nu = 0.2f;
        for (int arm : new int[]{1, 4, 12}) {
            float ref = NativePFSFBridge.nativeTimoshenkoMomentFactor(b, h, arm, youngs, nu);
            ByteBuffer bOut = floatDbb(1);
            int code = PFSFTickPlanner.forIsland(7013)
                    .pushTimoshenko(NativePFSFBridge.nativeDirectBufferAddress(bOut),
                                    b, h, arm, youngs, nu)
                    .execute();
            assertEquals(NativePFSFBridge.PFSFResult.OK, code);
            assertEquals(ref, readFloats(bOut, 1)[0], STRESS_ABS_TOL,
                    "timoshenko drift arm=" + arm);
        }
    }
}
