package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity harness for the v0.3c native PFSF bridge.
 *
 * <h2>Purpose</h2>
 * <p>Verifies that the {@link NativePFSFBridge} JNI surface is wired
 * correctly end-to-end: every native entry point advertised by the bridge
 * class is reachable, the {@link NativePFSFRuntime} façade converts
 * inactive-state calls into benign no-ops, and the {@link IPFSFRuntime}
 * adapter view honours the {@code KERNELS_PORTED} gate so call sites
 * transparently fall back to the Java solver until CI flips the flag.</p>
 *
 * <h2>Graceful skip</h2>
 * <p>CI runs in a GPU-less sandbox, so {@code libblockreality_pfsf}
 * is absent and {@link NativePFSFBridge#isAvailable()} returns
 * {@code false}. Tests that require the live native runtime assume it
 * via {@link Assumptions#assumeTrue(boolean)} and are reported as
 * <i>skipped</i> instead of failing — the Java reference path stays
 * authoritative in that configuration, which matches the intent of the
 * {@code KERNELS_PORTED=false} gate.</p>
 *
 * <h2>Flipping {@code KERNELS_PORTED}</h2>
 * <p>This harness does NOT flip the gate. Per the in-source comment in
 * {@link NativePFSFRuntime}, the flag is toggled only by a CI job that
 * compares {@code stress.bin} dumps produced by {@code pfsf_cli}
 * against the Java reference on the 20-island stress fixture. The
 * parity-within-1-ULP check is gated by GPU availability and cannot run
 * inside this unit-test harness.</p>
 */
class NativePFSFBridgeParityTest {

    // ═══════════════════════════════════════════════════════════════
    //  Class-loading / static initialiser parity
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NativePFSFBridge 類別載入不拋例外（庫缺失亦然）")
    void bridgeClassLoadsCleanly() {
        // Touching any static of the bridge runs its <clinit>, which in
        // turn calls System.loadLibrary. If the library is missing, the
        // initialiser logs and returns — it must never throw.
        assertDoesNotThrow(() -> {
            boolean available = NativePFSFBridge.isAvailable();
            String  version   = NativePFSFBridge.getVersion();
            assertNotNull(version, "getVersion() must never return null");
            if (!available) {
                assertEquals("n/a", version,
                        "Unloaded bridge should advertise 'n/a' version sentinel");
            }
        });
    }

    @Test
    @DisplayName("PFSFResult.describe 覆蓋所有已知代碼與 UNKNOWN 後備")
    void pfsfResultDescribeCoverage() {
        assertEquals("OK",          NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.OK));
        assertEquals("VULKAN",      NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_VULKAN));
        assertEquals("NO_DEVICE",   NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_NO_DEVICE));
        assertEquals("OUT_OF_VRAM", NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_OUT_OF_VRAM));
        assertEquals("INVALID_ARG", NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG));
        assertEquals("NOT_INIT",    NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_NOT_INIT));
        assertEquals("ISLAND_FULL", NativePFSFBridge.PFSFResult.describe(NativePFSFBridge.PFSFResult.ERROR_ISLAND_FULL));
        assertTrue(NativePFSFBridge.PFSFResult.describe(42).startsWith("UNKNOWN"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  NativePFSFRuntime inactive-state contract
    // ═══════════════════════════════════════════════════════════════
    //
    // With the shared library absent (CI) or the activation flag unset
    // (default), NativePFSFRuntime.active stays false. Every static
    // helper MUST short-circuit without touching a null JNI entry —
    // this mirrors the "fall back to Java" posture declared in the
    // class-level Javadoc.

    @Test
    @DisplayName("Runtime 靜態狀態：未啟用時 isActive=false")
    void runtimeInactiveWhenFlagOff() {
        // Default JVM config: -Dblockreality.native.pfsf is unset → false.
        // Library may or may not be loaded; active REQUIRES both flag +
        // library + successful init, so the stack cannot be active here.
        assertFalse(NativePFSFRuntime.isActive(),
                "Runtime must not report active without the activation flag");
    }

    @Test
    @DisplayName("Runtime 的 IPFSFRuntime 視圖未就緒時 isAvailable=false")
    void runtimeViewHonorsKernelsPortedGate() {
        IPFSFRuntime view = NativePFSFRuntime.asRuntime();
        assertNotNull(view, "asRuntime() must always return a singleton view");
        // Even if active==true (hypothetically in a dev build), the view
        // stays unavailable until KERNELS_PORTED flips. With the current
        // private constant at false, the view is guaranteed unavailable.
        assertFalse(view.isAvailable(),
                "View must stay unavailable until KERNELS_PORTED is flipped by CI");
    }

    @Test
    @DisplayName("Runtime getStats 回傳可讀診斷字串")
    void runtimeGetStatsStable() {
        String status = NativePFSFRuntime.getStatus();
        assertNotNull(status);
        assertTrue(status.startsWith("Native PFSF:"),
                "Status string must lead with 'Native PFSF:' prefix — got: " + status);
    }

    @Test
    @DisplayName("稀疏上傳 helper 未啟用時回傳 null / ERROR_NOT_INIT")
    void sparseHelpersInactiveFallback() {
        // active=false path — must not call into JNI and must return
        // benign sentinels so callers can route through Java untouched.
        ByteBuffer buf = NativePFSFRuntime.getSparseUploadBuffer(/*islandId*/ 0);
        assertNull(buf,
                "getSparseUploadBuffer must return null when runtime is not active");

        int rc = NativePFSFRuntime.notifySparseUpdates(/*islandId*/ 0, /*count*/ 1);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_NOT_INIT, rc,
                "notifySparseUpdates must return ERROR_NOT_INIT when inactive");
    }

    // ═══════════════════════════════════════════════════════════════
    //  IPFSFRuntime view — unavailable methods are benign no-ops
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("View 方法在未就緒時皆為無副作用 no-op")
    void viewMethodsNoOpWhenUnavailable() {
        IPFSFRuntime view = NativePFSFRuntime.asRuntime();
        // Every Strategy method must tolerate being called regardless of
        // availability — the safety contract declared in the class
        // Javadoc is that misconfigured boot cannot drive the simulation
        // into an inconsistent state.
        assertDoesNotThrow(() -> {
            view.setMaterialLookup(null);
            view.setAnchorLookup(null);
            view.setFillRatioLookup(null);
            view.setCuringLookup(null);
            view.setWindVector(null);
            view.removeBuffer(/*islandId*/ 0);
            view.notifyBlockChange(/*islandId*/ 0, null, null, null);
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  Live-library leg (skipped in GPU-less CI)
    // ═══════════════════════════════════════════════════════════════
    //
    // The following tests require libblockreality_pfsf to have loaded
    // successfully. When the library is absent they are reported as
    // SKIPPED, not FAILED — the intent is to verify the zero-copy DBB
    // path end-to-end whenever a developer runs the suite on a box with
    // the native build deployed, without breaking the default CI green.

    @Test
    @DisplayName("[live] nativeVersion 回傳非空字串")
    void liveVersionQuery() {
        Assumptions.assumeTrue(NativePFSFBridge.isAvailable(),
                "libblockreality_pfsf not loaded — skipping live-library leg");
        String v = NativePFSFBridge.getVersion();
        assertNotNull(v);
        assertNotEquals("n/a", v,
                "Loaded bridge must advertise a real version, not the sentinel");
    }

    @Test
    @DisplayName("[live] create → init → destroy 循環不洩漏 handle")
    void liveCreateInitDestroyCycle() {
        Assumptions.assumeTrue(NativePFSFBridge.isAvailable(),
                "libblockreality_pfsf not loaded — skipping live-library leg");

        // Small engine: 8^3 voxels, 1ms budget, 64MB VRAM, phase field + MG on.
        long h = NativePFSFBridge.nativeCreate(
                /* maxIslandSize    */ 8 * 8 * 8,
                /* tickBudgetMs     */ 1,
                /* vramBudgetBytes  */ 64L * 1024 * 1024,
                /* enablePhaseField */ true,
                /* enableMultigrid  */ true);
        Assumptions.assumeTrue(h != 0L,
                "pfsf_create returned 0 — no suitable Vulkan device, skipping");

        try {
            int rc = NativePFSFBridge.nativeInit(h);
            // Init may fail on a minimal CI box (no compute queue, etc.);
            // treat as a skip rather than a failure.
            Assumptions.assumeTrue(rc == NativePFSFBridge.PFSFResult.OK,
                    "pfsf_init failed: " + NativePFSFBridge.PFSFResult.describe(rc));

            assertTrue(NativePFSFBridge.nativeIsAvailable(h),
                    "nativeIsAvailable must be true after a successful init");

            long[] stats = NativePFSFBridge.nativeGetStats(h);
            assertNotNull(stats, "nativeGetStats must not return null on a live handle");
            assertEquals(5, stats.length,
                    "nativeGetStats contract: {islandCount, totalVoxels, vramUsed, vramBudget, lastTickMicros}");
        } finally {
            // Always destroy — never leak across the test suite.
            NativePFSFBridge.nativeShutdown(h);
            NativePFSFBridge.nativeDestroy(h);
        }
    }

    @Test
    @DisplayName("[live] sparse upload DBB 別名至 VMA 記憶體且容量充足")
    void liveSparseUploadBufferAliasing() {
        Assumptions.assumeTrue(NativePFSFBridge.isAvailable(),
                "libblockreality_pfsf not loaded — skipping live-library leg");

        long h = NativePFSFBridge.nativeCreate(8 * 8 * 8, 1, 64L * 1024 * 1024, true, true);
        Assumptions.assumeTrue(h != 0L, "pfsf_create returned 0");
        try {
            int rc = NativePFSFBridge.nativeInit(h);
            Assumptions.assumeTrue(rc == NativePFSFBridge.PFSFResult.OK,
                    "pfsf_init failed: " + NativePFSFBridge.PFSFResult.describe(rc));

            final int islandId = 1;
            int addRc = NativePFSFBridge.nativeAddIsland(h, islandId, 0, 0, 0, 8, 8, 8);
            Assumptions.assumeTrue(addRc == NativePFSFBridge.PFSFResult.OK,
                    "nativeAddIsland failed: " + NativePFSFBridge.PFSFResult.describe(addRc));

            ByteBuffer upload = NativePFSFBridge.nativeGetSparseUploadBuffer(h, islandId);
            assertNotNull(upload, "Sparse upload buffer must alias a live VMA allocation");
            assertTrue(upload.isDirect(), "Sparse upload buffer must be direct");
            // Capacity: MAX_SPARSE_UPDATES_PER_TICK(512) × 48 bytes = 24576.
            // The native side may round up to an alignment boundary — accept ≥ min.
            assertTrue(upload.capacity() >= 512 * 48,
                    "Sparse upload capacity below 24576 bytes: " + upload.capacity());

            // Dispatch with zero records must be a safe no-op.
            int notifyRc = NativePFSFBridge.nativeNotifySparseUpdates(h, islandId, 0);
            assertEquals(NativePFSFBridge.PFSFResult.OK, notifyRc,
                    "nativeNotifySparseUpdates with 0 records must succeed: "
                            + NativePFSFBridge.PFSFResult.describe(notifyRc));

            NativePFSFBridge.nativeRemoveIsland(h, islandId);
        } finally {
            NativePFSFBridge.nativeShutdown(h);
            NativePFSFBridge.nativeDestroy(h);
        }
    }
}
