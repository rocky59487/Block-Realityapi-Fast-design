package com.blockreality.api.physics.fluid;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI wrapper for {@code libblockreality_fluid} — v0.3c M3 native fluid
 * solver. Sibling to {@link com.blockreality.api.physics.pfsf.NativePFSFBridge};
 * same graceful-fallback pattern applies (missing library ⇒
 * {@link #isAvailable()} returns {@code false} and callers go down the
 * existing Java {@link FluidGPUEngine} path).
 *
 * <p>Activation flag: {@code -Dblockreality.native.fluid=true}. The library
 * load attempt runs regardless so operators can diagnose availability.</p>
 */
public final class NativeFluidBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fluid-Native");

    private static final boolean LIBRARY_LOADED;
    private static final String  VERSION_STRING;

    static {
        boolean loaded = false;
        String  version = "n/a";
        try {
            System.loadLibrary("blockreality_fluid");
            version = nativeVersion();
            loaded  = true;
            LOGGER.info("NativeFluidBridge loaded: blockreality_fluid v{}", version);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("NativeFluidBridge skipped: blockreality_fluid not found — Java fluid path will be used. ({})",
                    e.getMessage());
        } catch (Throwable t) {
            LOGGER.warn("NativeFluidBridge failed to initialise: {}", t.toString());
        }
        LIBRARY_LOADED = loaded;
        VERSION_STRING = version;
    }

    private NativeFluidBridge() {}

    public static boolean isAvailable() { return LIBRARY_LOADED; }
    public static String  getVersion()  { return VERSION_STRING;  }

    /** Mirrors {@code fluid_result}. Values parallel the PFSF codes. */
    public static final class Result {
        public static final int OK                =  0;
        public static final int ERROR_VULKAN      = -1;
        public static final int ERROR_NO_DEVICE   = -2;
        public static final int ERROR_OUT_OF_VRAM = -3;
        public static final int ERROR_INVALID_ARG = -4;
        public static final int ERROR_NOT_INIT    = -5;

        private Result() {}
    }

    // ─── Native entry points ──────────────────────────────────────────────

    public static native long   nativeCreate(int maxIslandSize,
                                              int tickBudgetMs,
                                              long vramBudgetBytes,
                                              boolean enableSurfaceTension,
                                              boolean enableCoupling);
    public static native int    nativeInit(long handle);
    public static native void   nativeShutdown(long handle);
    public static native void   nativeDestroy(long handle);
    public static native boolean nativeIsAvailable(long handle);

    public static native int    nativeAddIsland(long handle, int islandId, int lx, int ly, int lz);
    public static native void   nativeRemoveIsland(long handle, int islandId);

    /**
     * @param pressure  float32 × N
     * @param velocity  float32 × 3N (AoS xyz)
     * @param flux      float32 × 6N (SoA per direction)
     * @param levelSet  float32 × N (signed distance)
     */
    public static native int    nativeRegisterIslandBuffers(long handle,
                                                              int islandId,
                                                              ByteBuffer pressure,
                                                              ByteBuffer velocity,
                                                              ByteBuffer flux,
                                                              ByteBuffer levelSet);

    public static native int    nativeTick(long handle, int[] dirtyIslandIds, long currentEpoch);

    private static native String nativeVersion();
}
