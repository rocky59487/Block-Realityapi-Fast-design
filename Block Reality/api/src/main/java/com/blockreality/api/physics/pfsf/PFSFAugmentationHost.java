package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * v0.3d Phase 5 — SPI augmentation host.
 *
 * <p>External mods plug per-voxel DirectByteBuffer slots into the PFSF
 * solver via this facade. Each slot corresponds to one SPI manager
 * (thermal field, fluid pressure, cable tension, …); the native
 * {@code libpfsf_compute} stores them in a process-wide registry keyed
 * by {@code (islandId, kind)} so the Phase 6 plan buffer can pick them
 * up at the appropriate stage boundary without round-tripping through
 * Java.</p>
 *
 * <p>Calling into this host is optional: when {@code compute.v5} isn't
 * available the methods short-circuit to no-ops and the engine falls
 * back to the default behaviour defined by each SPI's pre-v0.3d code
 * path (Java reference). Nothing crashes.</p>
 *
 * <p>Thread-safety: the native side uses a shared_mutex; concurrent
 * register/clear from different worker threads is safe.</p>
 */
public final class PFSFAugmentationHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Augmentation");

    private PFSFAugmentationHost() {}

    /**
     * Register or overwrite a slot.
     *
     * @param islandId   PFSF island identifier
     * @param kind       one of {@link NativePFSFBridge.AugKind}
     * @param dbb        direct byte buffer — must remain alive until
     *                   {@link #clear(int, int)} is called
     * @param strideBytes bytes per voxel entry (4 for a float field, 12
     *                   for a float3 field, …)
     * @param version    monotonically increasing version; bump when the
     *                   mod's contribution changes so the native side
     *                   knows to re-read.
     * @return whether the native registry accepted the slot.
     */
    public static boolean register(int islandId, int kind, ByteBuffer dbb,
                                     int strideBytes, int version) {
        if (!NativePFSFBridge.hasComputeV5()) return false;
        if (dbb == null || !dbb.isDirect()) {
            LOGGER.warn("[PFSF-Aug] register ignored — buffer is null or non-direct");
            return false;
        }
        try {
            int result = NativePFSFBridge.nativeAugRegister(islandId, kind, dbb,
                    strideBytes, version);
            return result == NativePFSFBridge.PFSFResult.OK;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Clear one slot — no-op when not registered. */
    public static void clear(int islandId, int kind) {
        if (!NativePFSFBridge.hasComputeV5()) return;
        try {
            NativePFSFBridge.nativeAugClear(islandId, kind);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /** Clear every slot registered to an island (call on island dispose). */
    public static void clearIsland(int islandId) {
        if (!NativePFSFBridge.hasComputeV5()) return;
        try {
            NativePFSFBridge.nativeAugClearIsland(islandId);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /** @return number of augmentation slots currently attached to an island. */
    public static int islandCount(int islandId) {
        if (!NativePFSFBridge.hasComputeV5()) return 0;
        try {
            return NativePFSFBridge.nativeAugIslandCount(islandId);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /**
     * @return version number of the registered slot, or -1 if not
     *         present. Useful for content-change detection.
     */
    public static int queryVersion(int islandId, int kind) {
        if (!NativePFSFBridge.hasComputeV5()) return -1;
        try {
            return NativePFSFBridge.nativeAugQueryVersion(islandId, kind);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }
}
