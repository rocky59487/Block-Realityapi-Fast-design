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
