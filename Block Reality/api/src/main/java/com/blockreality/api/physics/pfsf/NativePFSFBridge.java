package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI wrapper for the v0.3c native PFSF runtime ({@code libblockreality_pfsf}).
 *
 * <p>This class provides low-level access to the C API declared in
 * {@code L1-native/libpfsf/include/pfsf/pfsf.h}. It is intentionally thin —
 * higher-level Java callers should go through {@link NativePFSFRuntime},
 * which wraps the handle lifecycle and applies the same callback/logging
 * discipline as the existing Java solver.</p>
 *
 * <p>If {@code System.loadLibrary("blockreality_pfsf")} fails (shared library
 * not on {@code java.library.path} or missing Vulkan SDK on the host),
 * {@link #isAvailable()} returns {@code false} and calling any {@code native*}
 * method is a programming error. Upstream code MUST check availability and
 * fall back to the existing Java path — this mirrors the
 * {@link com.blockreality.api.client.render.rt.BRNRDNative} pattern.</p>
 *
 * <p>Activation flag: {@code -Dblockreality.native.pfsf=true}. The loader
 * still attempts {@code loadLibrary} regardless, so operators can eagerly
 * detect native availability and report it, but the Java façade refuses
 * to delegate unless the flag is explicitly set (Phase 1 safety posture).</p>
 */
public final class NativePFSFBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Native");

    private static final boolean LIBRARY_LOADED;
    private static final String  VERSION_STRING;

    static {
        boolean loaded = false;
        String  version = "n/a";
        try {
            System.loadLibrary("blockreality_pfsf");
            loaded  = true;
            version = nativeVersion();
            LOGGER.info("NativePFSFBridge loaded: blockreality_pfsf v{} ready", version);
        } catch (UnsatisfiedLinkError e) {
            // Expected when the native binary is absent (developer builds,
            // unsupported platforms, or when the operator hasn't run :api:nativeBuild).
            LOGGER.info("NativePFSFBridge skipped: blockreality_pfsf not found — Java solver will be used. ({})",
                    e.getMessage());
        } catch (Throwable t) {
            LOGGER.warn("NativePFSFBridge failed to initialise: {}", t.toString());
        }
        LIBRARY_LOADED = loaded;
        VERSION_STRING = version;
    }

    private NativePFSFBridge() {}

    /** @return whether {@code libblockreality_pfsf} loaded successfully. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    /** Native library version string ({@code "0.1.0"} etc.), or {@code "n/a"} if unloaded. */
    public static String getVersion() {
        return VERSION_STRING;
    }

    // ── Native entry points (all jlong handles are opaque pfsf_engine) ──────

    /** Creates a new engine handle. Returns {@code 0} on allocation failure. */
    public static native long nativeCreate(int maxIslandSize,
                                            int tickBudgetMs,
                                            long vramBudgetBytes,
                                            boolean enablePhaseField,
                                            boolean enableMultigrid);

    /** Initialises Vulkan + pipelines. Returns a {@link PFSFResult} code. */
    public static native int nativeInit(long handle);

    public static native void nativeShutdown(long handle);

    public static native void nativeDestroy(long handle);

    public static native boolean nativeIsAvailable(long handle);

    /**
     * Thread-safe stats query.
     *
     * @return {@code long[5]} = {@code {islandCount, totalVoxels, vramUsed,
     *         vramBudget, lastTickMicros}} or {@code null} on failure.
     */
    public static native long[] nativeGetStats(long handle);

    public static native void nativeSetWind(long handle, float wx, float wy, float wz);

    public static native int nativeAddIsland(long handle,
                                              int islandId,
                                              int originX, int originY, int originZ,
                                              int lx, int ly, int lz);

    public static native void nativeRemoveIsland(long handle, int islandId);

    public static native void nativeMarkFullRebuild(long handle, int islandId);

    /**
     * Registers a sparse voxel update.
     *
     * @param cond6 conductivity in the 6-direction SoA order (see
     *              {@code pfsf_direction}). Length must be ≥ 6.
     */
    public static native int nativeNotifyBlockChange(long handle,
                                                      int islandId,
                                                      int flatIndex,
                                                      float source,
                                                      int voxelType,
                                                      float maxPhi,
                                                      float rcomp,
                                                      float[] cond6);

    /**
     * Runs one tick.
     *
     * @param dirtyIslandIds  dirty island ids for this epoch (may be {@code null}).
     * @param currentEpoch    monotonic epoch counter.
     * @param outFailures     caller-sized int[]; on return, {@code out[0]} holds
     *                        the failure count, followed by {@code count} tuples
     *                        of 4 ints: {@code x, y, z, failureType}. May be
     *                        {@code null} if the caller does not need failure data.
     * @return {@link PFSFResult} code.
     */
    public static native int nativeTick(long handle,
                                         int[] dirtyIslandIds,
                                         long currentEpoch,
                                         int[] outFailures);

    /**
     * Reads the stress utilisation ratio for an island.
     *
     * @return number of floats written on success, or a negative
     *         {@link PFSFResult} code on failure.
     */
    public static native int nativeReadStress(long handle, int islandId, float[] outStress);

    /** libpfsf version string. */
    private static native String nativeVersion();

    /** Mirrors the {@code pfsf_result} enum. */
    public static final class PFSFResult {
        public static final int OK                 =  0;
        public static final int ERROR_VULKAN       = -1;
        public static final int ERROR_NO_DEVICE    = -2;
        public static final int ERROR_OUT_OF_VRAM  = -3;
        public static final int ERROR_INVALID_ARG  = -4;
        public static final int ERROR_NOT_INIT     = -5;
        public static final int ERROR_ISLAND_FULL  = -6;

        private PFSFResult() {}

        public static String describe(int code) {
            return switch (code) {
                case OK                -> "OK";
                case ERROR_VULKAN      -> "VULKAN";
                case ERROR_NO_DEVICE   -> "NO_DEVICE";
                case ERROR_OUT_OF_VRAM -> "OUT_OF_VRAM";
                case ERROR_INVALID_ARG -> "INVALID_ARG";
                case ERROR_NOT_INIT    -> "NOT_INIT";
                case ERROR_ISLAND_FULL -> "ISLAND_FULL";
                default                -> "UNKNOWN(" + code + ")";
            };
        }
    }
}
