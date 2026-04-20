package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.3e M6 — PFSF CPU microbenchmark harness.
 *
 * <p>Disabled by default; set {@code -Dpfsf.bench=true} to run. The harness
 * times four workloads that together cover the per-tick CPU primitives
 * crossing the JNI boundary:</p>
 *
 * <ul>
 *   <li>{@code normalize_soa6_64k} — SoA-6 normalisation over 64k voxels
 *       (bandwidth-bound, maps directly onto the hot loop in
 *       {@link PFSFDataBuilder#normalizeSoA6}).</li>
 *   <li>{@code chebyshev_table_64} — precomputed Chebyshev omega schedule
 *       (64 entries, branch-light, measures per-call setup cost for the
 *       solver scheduler).</li>
 *   <li>{@code apply_wind_bias_64k} — in-place conductivity bias apply
 *       over 64k × 6 directions.</li>
 *   <li>{@code tick50k_surrogate} — 100 iterations of (normalize + wind
 *       bias + omega table) on a 50k-voxel working set. This is the
 *       gate target: native ≥ 1.4× Java is the v0.3d acceptance criterion
 *       for the full tick-boundary amortisation pipeline.</li>
 * </ul>
 *
 * <p>Each workload is measured in two modes:</p>
 *
 * <ol>
 *   <li><b>Java ref</b> — always available, calls package-private
 *       {@code *JavaRef} static helpers. The ref path is the v0.3d
 *       golden oracle and must not regress &gt; 5% from the pinned
 *       baseline in {@code benchmarks/baselines/v0.3e-linux-x64.json}.</li>
 *   <li><b>Native</b> — gated on {@link NativePFSFBridge#hasComputeV1}
 *       and {@link NativePFSFBridge#hasComputeV4}. When the .so is absent
 *       the native column is recorded as {@code null}, meaning the gate
 *       enforces only the Java-ref regression check. On CI runners with
 *       Vulkan SDK + {@code -Pblockreality.native.build=true}, native
 *       numbers are expected to land within the baseline's
 *       {@code native_over_java_min_ratio} budget.</li>
 * </ol>
 *
 * <p>Methodology: 5 untimed warmup iterations + 20 measured iterations,
 * reporting median (tighter than mean against GC jitter and OS noise).
 * Per-iteration work scale is chosen so each measurement runs for
 * roughly 0.5–5 ms, keeping System.nanoTime resolution errors under 1%.</p>
 *
 * <p>Output: JSON written to {@code build/pfsf-bench/results.json}
 * (path overridable via {@code -Dpfsf.bench.out=...}). Schema is
 * consumed by {@code scripts/pfsf_perf_gate.py} which compares against
 * the pinned baseline and fails CI on regression.</p>
 */
@EnabledIfSystemProperty(named = "pfsf.bench", matches = "true")
class PfsfBenchmark {

    private static final int WARMUP_ITERS   = 5;
    private static final int MEASURE_ITERS  = 20;

    // ── Workload sizes ──────────────────────────────────────────────────

    private static final int SIZE_64K   = 64 * 1024;
    private static final int SIZE_50K   = 50_000;
    private static final int TICK_REPS  = 100;
    private static final float RHO_SPEC = 0.98f;

    // ── Data cache (same arrays reused across modes so the CPU caches see
    //    identical memory pressure in both paths) ──────────────────────

    private final SplittableRandom rng = new SplittableRandom(0xBEEFL);

    @Test
    @DisplayName("run all benchmarks and emit results JSON")
    void runAll() throws IOException {
        List<Result> results = new ArrayList<>();

        results.add(benchNormalizeSoA6(SIZE_64K));
        results.add(benchChebyshevTable(64));
        results.add(benchApplyWindBias(SIZE_64K));
        results.add(benchTickSurrogate(SIZE_50K, TICK_REPS));

        Path out = Paths.get(System.getProperty("pfsf.bench.out",
                "build/pfsf-bench/results.json"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, renderJson(results));

        // Sanity: every workload recorded a Java-ref measurement. Native
        // can be absent (no .so on this runner); we don't require it.
        for (Result r : results) {
            assertTrue(r.javaNsPerOp > 0, () -> r.name + " missing javaRef measurement");
        }
    }

    // ── Workloads ───────────────────────────────────────────────────────

    private Result benchNormalizeSoA6(int n) {
        // Fresh arrays per iteration — normalizeSoA6 mutates in place, so
        // reusing would leave the data already normalised on iter 2+.
        float[] source       = new float[n];
        float[] rcomp        = new float[n];
        float[] rtens        = new float[n];
        float[] conductivity = new float[n * 6];

        // Golden data is independent of the iteration body, hoist fill out.
        float[] goldSource = randArray(n, 0.1f, 10.0f);
        float[] goldRcomp  = randArray(n, 5.0f, 50.0f);
        float[] goldRtens  = randArray(n, 1.0f, 10.0f);
        float[] goldCond   = randArray(n * 6, 0.1f, 5.0f);

        Runnable javaRef = () -> {
            System.arraycopy(goldSource, 0, source, 0, n);
            System.arraycopy(goldRcomp,  0, rcomp,  0, n);
            System.arraycopy(goldRtens,  0, rtens,  0, n);
            System.arraycopy(goldCond,   0, conductivity, 0, n * 6);
            PFSFDataBuilder.normalizeSoA6JavaRef(source, rcomp, rtens, conductivity, n);
        };

        Runnable native_ = (NativePFSFBridge.isAvailable() && NativePFSFBridge.hasComputeV1())
                ? () -> {
                    System.arraycopy(goldSource, 0, source, 0, n);
                    System.arraycopy(goldRcomp,  0, rcomp,  0, n);
                    System.arraycopy(goldRtens,  0, rtens,  0, n);
                    System.arraycopy(goldCond,   0, conductivity, 0, n * 6);
                    NativePFSFBridge.nativeNormalizeSoA6(
                            source, rcomp, rtens, conductivity, null, n);
                }
                : null;

        return measure("normalize_soa6_64k", n, javaRef, native_);
    }

    private Result benchChebyshevTable(int size) {
        float[] out = new float[size];
        float rho  = RHO_SPEC;

        Runnable javaRef = () -> {
            float[] tab = PFSFScheduler.precomputeOmegaTableJavaRef(rho);
            // Consume the result to defeat DCE.
            System.arraycopy(tab, 0, out, 0, Math.min(size, tab.length));
        };

        Runnable native_ = (NativePFSFBridge.isAvailable() && NativePFSFBridge.hasComputeV4())
                ? () -> NativePFSFBridge.nativePrecomputeOmegaTable(rho, out)
                : null;

        return measure("chebyshev_table_64", size, javaRef, native_);
    }

    private Result benchApplyWindBias(int n) {
        float[] conductivity = new float[n * 6];
        float[] gold         = randArray(n * 6, 0.1f, 5.0f);

        // Java-ref: the pure-java in-place wind bias loop. Matches the C
        // reference in pfsf_compute.h verbatim (same sign convention and
        // direction ordering) — kept local here so the bench remains
        // package-private-only (PFSFSourceBuilder delegates via the solver
        // which requires a full island context we don't want to materialise
        // for a microbench).
        Runnable javaRef = () -> {
            System.arraycopy(gold, 0, conductivity, 0, n * 6);
            applyWindBiasJavaRef(conductivity, n, 1.0f, 0.0f, 0.0f, 0.25f);
        };

        Runnable native_ = (NativePFSFBridge.isAvailable() && NativePFSFBridge.hasComputeV1())
                ? () -> {
                    System.arraycopy(gold, 0, conductivity, 0, n * 6);
                    NativePFSFBridge.nativeApplyWindBias(
                            conductivity, n, 1.0f, 0.0f, 0.0f, 0.25f);
                }
                : null;

        return measure("apply_wind_bias_64k", n, javaRef, native_);
    }

    private Result benchTickSurrogate(int n, int reps) {
        float[] source       = new float[n];
        float[] rcomp        = new float[n];
        float[] rtens        = new float[n];
        float[] conductivity = new float[n * 6];
        float[] omegaTable   = new float[64];

        float[] goldSource = randArray(n, 0.1f, 10.0f);
        float[] goldRcomp  = randArray(n, 5.0f, 50.0f);
        float[] goldRtens  = randArray(n, 1.0f, 10.0f);
        float[] goldCond   = randArray(n * 6, 0.1f, 5.0f);

        Runnable javaRef = () -> {
            for (int k = 0; k < reps; k++) {
                System.arraycopy(goldSource, 0, source, 0, n);
                System.arraycopy(goldRcomp,  0, rcomp,  0, n);
                System.arraycopy(goldRtens,  0, rtens,  0, n);
                System.arraycopy(goldCond,   0, conductivity, 0, n * 6);
                PFSFDataBuilder.normalizeSoA6JavaRef(source, rcomp, rtens, conductivity, n);
                applyWindBiasJavaRef(conductivity, n, 1.0f, 0.0f, 0.0f, 0.25f);
                float[] tab = PFSFScheduler.precomputeOmegaTableJavaRef(RHO_SPEC);
                System.arraycopy(tab, 0, omegaTable, 0, Math.min(64, tab.length));
            }
        };

        Runnable native_ = (NativePFSFBridge.isAvailable()
                          && NativePFSFBridge.hasComputeV1()
                          && NativePFSFBridge.hasComputeV4())
                ? () -> {
                    for (int k = 0; k < reps; k++) {
                        System.arraycopy(goldSource, 0, source, 0, n);
                        System.arraycopy(goldRcomp,  0, rcomp,  0, n);
                        System.arraycopy(goldRtens,  0, rtens,  0, n);
                        System.arraycopy(goldCond,   0, conductivity, 0, n * 6);
                        NativePFSFBridge.nativeNormalizeSoA6(
                                source, rcomp, rtens, conductivity, null, n);
                        NativePFSFBridge.nativeApplyWindBias(
                                conductivity, n, 1.0f, 0.0f, 0.0f, 0.25f);
                        NativePFSFBridge.nativePrecomputeOmegaTable(RHO_SPEC, omegaTable);
                    }
                }
                : null;

        // Report per-rep (not per-iteration) so the native_over_java ratio is
        // a direct tick-boundary amortisation figure.
        return measure("tick50k_surrogate", n * reps, javaRef, native_);
    }

    // ── Timing core ─────────────────────────────────────────────────────

    private static Result measure(String name, int workSize, Runnable javaRef, Runnable nativeRun) {
        for (int i = 0; i < WARMUP_ITERS; i++) javaRef.run();
        double javaNsPerOp = medianTiming(javaRef, MEASURE_ITERS, workSize);

        Double nativeNsPerOp = null;
        if (nativeRun != null) {
            for (int i = 0; i < WARMUP_ITERS; i++) nativeRun.run();
            nativeNsPerOp = medianTiming(nativeRun, MEASURE_ITERS, workSize);
        }

        return new Result(name, workSize, javaNsPerOp, nativeNsPerOp);
    }

    private static double medianTiming(Runnable body, int iters, int workSize) {
        long[] samples = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            body.run();
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);
        long medianNs = samples[iters / 2];
        return (double) medianNs / (double) workSize;
    }

    private float[] randArray(int n, float lo, float hi) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = lo + rng.nextFloat() * (hi - lo);
        }
        return a;
    }

    // ── Java reference: apply_wind_bias ─────────────────────────────────

    /**
     * Pure-Java mirror of {@code pfsf_apply_wind_bias} (see
     * {@code wind_bias.cpp}). Direction order follows
     * {@code pfsf_direction}: {@code NEG_X, POS_X, NEG_Y, POS_Y, NEG_Z, POS_Z}.
     * SoA-6 layout: {@code conductivity[d * n + i]}.
     *
     * <p>Applies Anderson 2010 first-order upwind bias only to the four
     * horizontal edges (X/Z), with a piecewise update based on the sign of
     * {@code step · wind_xz}: {@code *= (1+k)} on the upwind side,
     * {@code /= (1+k)} on the downwind side. ±Y edges are unchanged.
     * Must mirror {@code pfsf_apply_wind_bias} bit-exactly so the
     * benchmark ratio is meaningful.</p>
     */
    static void applyWindBiasJavaRef(float[] conductivity, int n,
                                     float wx, float wy, float wz,
                                     float k) {
        if (wx == 0.0f && wz == 0.0f) return;   // no horizontal wind
        final float kPlus = 1.0f + k;
        final int[] stepX = { -1, +1,  0,  0,  0,  0 };
        final int[] stepZ = {  0,  0,  0,  0, -1, +1 };
        for (int d = 0; d < 6; d++) {
            if (d == 2 || d == 3) continue;     // skip ±Y
            float dot = stepX[d] * wx + stepZ[d] * wz;
            if (dot == 0.0f) continue;
            int base = d * n;
            if (dot > 0.0f) {
                for (int i = 0; i < n; i++) conductivity[base + i] *= kPlus;
            } else {
                final float inv = 1.0f / kPlus;
                for (int i = 0; i < n; i++) conductivity[base + i] *= inv;
            }
        }
    }

    // ── JSON rendering (no extra deps) ──────────────────────────────────

    private static String renderJson(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema_version\": 1,\n");
        sb.append("  \"os\": ").append(quote(System.getProperty("os.name"))).append(",\n");
        sb.append("  \"arch\": ").append(quote(System.getProperty("os.arch"))).append(",\n");
        sb.append("  \"jvm\": ").append(quote(System.getProperty("java.version"))).append(",\n");
        sb.append("  \"native_loaded\": ").append(NativePFSFBridge.isAvailable()).append(",\n");
        sb.append("  \"native_version\": ").append(quote(NativePFSFBridge.getVersion())).append(",\n");
        sb.append("  \"warmup_iters\": ").append(WARMUP_ITERS).append(",\n");
        sb.append("  \"measure_iters\": ").append(MEASURE_ITERS).append(",\n");
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append("    {");
            sb.append("\"name\": ").append(quote(r.name)).append(", ");
            sb.append("\"work_size\": ").append(r.workSize).append(", ");
            sb.append("\"java_ns_per_op\": ").append(String.format(java.util.Locale.ROOT, "%.3f", r.javaNsPerOp)).append(", ");
            sb.append("\"native_ns_per_op\": ");
            if (r.nativeNsPerOp == null) sb.append("null");
            else sb.append(String.format(java.util.Locale.ROOT, "%.3f", r.nativeNsPerOp));
            sb.append(", \"native_over_java\": ");
            if (r.nativeNsPerOp == null || r.nativeNsPerOp <= 0) sb.append("null");
            else sb.append(String.format(java.util.Locale.ROOT, "%.3f", r.javaNsPerOp / r.nativeNsPerOp));
            sb.append("}");
            if (i + 1 < results.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── Record type (Java 17-friendly POJO, no records to keep style
    //    consistent with the rest of this module) ──────────────────────

    private static final class Result {
        final String name;
        final int    workSize;
        final double javaNsPerOp;
        final Double nativeNsPerOp;

        Result(String name, int workSize, double javaNsPerOp, Double nativeNsPerOp) {
            this.name          = name;
            this.workSize      = workSize;
            this.javaNsPerOp   = javaNsPerOp;
            this.nativeNsPerOp = nativeNsPerOp;
        }
    }

    // Keeps the unused-symbol reaper at bay — touched by renderJson.
    @SuppressWarnings("unused")
    private static final List<String> PRIMITIVES = Collections.unmodifiableList(Arrays.asList(
            "normalize_soa6_64k",
            "chebyshev_table_64",
            "apply_wind_bias_64k",
            "tick50k_surrogate"));
}
