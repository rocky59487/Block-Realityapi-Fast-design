package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.4 M2h — verifies the version-bump contract of
 * {@link PFSFAugmentationHost#publish}. The contract:
 *
 * <ul>
 *   <li>A fresh slot starts at version 1 on first publish.</li>
 *   <li>Re-publishing with the same buffer (content update) bumps the
 *       version monotonically so the native dispatcher knows to re-read
 *       the DBB — even when the pointer identity didn't change.</li>
 *   <li>{@link PFSFAugmentationHost#clear} resets the slot; the next
 *       publish starts at 1 again (fresh AtomicInteger instance).</li>
 *   <li>Strong reference tracking is symmetric with publish/clear so a
 *       GC between publish and native read can't reclaim the buffer.</li>
 * </ul>
 *
 * <p>Pure-Java: runs on every CI box, no GPU required. A parallel leg
 * gated on {@code hasComputeV5()} would also verify
 * {@link NativePFSFBridge#nativeAugQueryVersion} agrees; on the GPU-less
 * runner the native check skips via {@link org.junit.jupiter.api.Assumptions}.
 */
class AugVersionBumpTest {

    private static final int ISLAND = 0xBEEF1234;
    private static final int KIND   = NativePFSFBridge.AugKind.THERMAL_FIELD;

    @BeforeEach
    void clean() { PFSFAugmentationHost.clearIsland(ISLAND); }

    @AfterEach
    void reset() { PFSFAugmentationHost.clearIsland(ISLAND); }

    private static ByteBuffer allocDbb(int n) {
        return ByteBuffer.allocateDirect(n * Float.BYTES).order(ByteOrder.nativeOrder());
    }

    @Test
    @DisplayName("First publish assigns version 1; second bumps to 2")
    void firstPublishVersion1BumpsTo2() {
        ByteBuffer dbb = allocDbb(8);
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        assertEquals(1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND));

        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        assertEquals(2, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "second publish must bump version monotonically");
    }

    @Test
    @DisplayName("Same-buffer re-publish bumps version even without pointer change")
    void sameBufferRepublishBumps() {
        ByteBuffer dbb = allocDbb(32);
        for (int i = 0; i < 10; ++i) {
            PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        }
        assertEquals(10, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "10 publish calls must yield version 10");
        assertTrue(PFSFAugmentationHost.hasStrongRef(ISLAND, KIND),
                "strong ref must be held while slot is published");
    }

    @Test
    @DisplayName("Different buffer overwrites strong ref and still bumps version")
    void bufferSwapBumpsVersion() {
        ByteBuffer a = allocDbb(16);
        ByteBuffer b = allocDbb(16);
        PFSFAugmentationHost.publish(ISLAND, KIND, a, Float.BYTES);
        assertEquals(1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND));
        PFSFAugmentationHost.publish(ISLAND, KIND, b, Float.BYTES);
        assertEquals(2, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND));
        /* After swap, still exactly one strong ref (to b, which overwrote a). */
        assertTrue(PFSFAugmentationHost.hasStrongRef(ISLAND, KIND));
    }

    @Test
    @DisplayName("clear() resets version and drops strong ref")
    void clearResetsVersion() {
        ByteBuffer dbb = allocDbb(8);
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        assertEquals(2, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND));

        PFSFAugmentationHost.clear(ISLAND, KIND);
        assertEquals(-1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "clear should drop the version entry entirely");
        assertFalse(PFSFAugmentationHost.hasStrongRef(ISLAND, KIND),
                "clear should drop the strong ref");

        /* Next publish starts fresh at 1, not resumes from 3. */
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        assertEquals(1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "post-clear publish must restart the version counter");
    }

    @Test
    @DisplayName("clearIsland drops every kind attached to that island")
    void clearIslandDropsAllKinds() {
        ByteBuffer a = allocDbb(4);
        ByteBuffer b = allocDbb(4);
        PFSFAugmentationHost.publish(ISLAND, NativePFSFBridge.AugKind.THERMAL_FIELD, a, Float.BYTES);
        PFSFAugmentationHost.publish(ISLAND, NativePFSFBridge.AugKind.FLUID_PRESSURE, b, Float.BYTES);

        assertTrue(PFSFAugmentationHost.hasStrongRef(ISLAND,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertTrue(PFSFAugmentationHost.hasStrongRef(ISLAND,
                NativePFSFBridge.AugKind.FLUID_PRESSURE));

        PFSFAugmentationHost.clearIsland(ISLAND);
        assertFalse(PFSFAugmentationHost.hasStrongRef(ISLAND,
                NativePFSFBridge.AugKind.THERMAL_FIELD));
        assertFalse(PFSFAugmentationHost.hasStrongRef(ISLAND,
                NativePFSFBridge.AugKind.FLUID_PRESSURE));
    }

    @Test
    @DisplayName("Null / non-direct buffers are rejected without touching version")
    void rejectsMalformedBuffer() {
        /* publish with null DBB — should not mutate any state. */
        boolean ok = PFSFAugmentationHost.publish(ISLAND, KIND, null, Float.BYTES);
        assertFalse(ok);
        assertEquals(-1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "null buffer must not create a version entry");

        /* publish with a heap buffer (not direct) — same rejection. */
        ByteBuffer heap = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        boolean ok2 = PFSFAugmentationHost.publish(ISLAND, KIND, heap, Float.BYTES);
        assertFalse(ok2);
        assertEquals(-1, PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND),
                "heap buffer must not create a version entry");
    }

    @Test
    @DisplayName("[live] native-side version agrees with the Java counter")
    void liveNativeVersionAgreement() {
        assumeTrue(NativePFSFBridge.hasComputeV5(),
                "libpfsf_compute compute.v5 not available — skipping");

        ByteBuffer dbb = allocDbb(64);
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);
        PFSFAugmentationHost.publish(ISLAND, KIND, dbb, Float.BYTES);

        int local  = PFSFAugmentationHost.queryLocalVersion(ISLAND, KIND);
        int native_ = PFSFAugmentationHost.queryVersion(ISLAND, KIND);
        assertTrue(native_ >= 0, "live native side must report a version");
        assertEquals(local, native_,
                "Java counter and native registry must report identical versions");
    }
}
