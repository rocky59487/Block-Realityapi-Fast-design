package com.blockreality.api.client.render.rt;

import java.nio.ByteBuffer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI wrapper for {@code libblockreality_render} — v0.3c M4 native
 * Vulkan RT dispatchers, BLAS/TLAS, ReSTIR/DDGI/ReLAX. Loaded on
 * client-side startup; activation gated by the same
 * {@code -Dblockreality.native.render=true} flag as the other v0.3c
 * subsystems. Same graceful-fallback pattern as the PFSF / Fluid
 * bridges: missing library ⇒ {@link #isAvailable()} returns {@code false}
 * and the classic Java render path stays live.
 */
@OnlyIn(Dist.CLIENT)
public final class NativeRenderBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Render-Native");

    private static final boolean LIBRARY_LOADED;
    private static final String  VERSION_STRING;

    static {
        boolean loaded = false;
        String  version = "n/a";
        try {
            System.loadLibrary("blockreality_render");
            version = nativeVersion();
            loaded  = true;
            LOGGER.info("NativeRenderBridge loaded: blockreality_render v{}", version);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("NativeRenderBridge skipped: blockreality_render not found — Java RT path will be used. ({})",
                    e.getMessage());
        } catch (Throwable t) {
            LOGGER.warn("NativeRenderBridge failed to initialise: {}", t.toString());
        }
        LIBRARY_LOADED = loaded;
        VERSION_STRING = version;
    }

    private NativeRenderBridge() {}

    public static boolean isAvailable() { return LIBRARY_LOADED; }
    public static String  getVersion()  { return VERSION_STRING;  }

    /** Mirrors {@code render_result}. */
    public static final class Result {
        public static final int OK                =  0;
        public static final int ERROR_VULKAN      = -1;
        public static final int ERROR_NO_DEVICE   = -2;
        public static final int ERROR_OUT_OF_VRAM = -3;
        public static final int ERROR_INVALID_ARG = -4;
        public static final int ERROR_NOT_INIT    = -5;
        public static final int ERROR_NO_RT       = -6;

        private Result() {}
    }

    /** Mirrors {@code render_tier}. */
    public static final class Tier {
        public static final int FALLBACK  = 0;
        public static final int ADA       = 1;
        public static final int BLACKWELL = 2;

        private Tier() {}
    }

    // ─── Native entry points ─────────────────────────────────────

    public static native long nativeCreate(int width, int height,
                                             long vramBudgetBytes,
                                             int tierOverride,
                                             boolean enableRestir,
                                             boolean enableDdgi,
                                             boolean enableRelax);

    public static native int     nativeInit(long handle);
    public static native void    nativeShutdown(long handle);
    public static native void    nativeDestroy(long handle);
    public static native boolean nativeIsAvailable(long handle);
    public static native int     nativeActiveTier(long handle);

    /** Camera UBO update — 256-byte aligned DBB. */
    public static native int     nativeUpdateCameraDbb(long handle, ByteBuffer cameraUbo);

    public static native int     nativeSubmitFrame(long handle, long frameIndex);

    private static native String nativeVersion();
}
