package com.blockreality.api.client.render.rt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI wrapper for NVIDIA Real-Time Denoisers (NRD) SDK.
 * 
 * Provides native bindings to blockreality_nrd.dll, allowing us to utilize
 * ReBLUR/ReLAX for temporal and spatial denoising of RT shadows and reflections.
 * If the library fails to load, the system degrades gracefully and delegates
 * denoising back to our fallback BRSVGFDenoiser.
 */
public class BRNRDNative {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRNRDNative.class);
    private static boolean nrdAvailable = false;

    static {
        try {
            System.loadLibrary("blockreality_nrd");
            nrdAvailable = true;
            LOGGER.info("BRNRDNative initialized: blockreality_nrd loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            nrdAvailable = false;
            LOGGER.info("BRNRDNative skipped: blockreality_nrd.dll not found. Will fallback to BRSVGFDenoiser.");
            // We only log as info to prevent panic, since SVGF fallback is a valid pipeline state.
        }
    }

    public static boolean isNrdAvailable() {
        return nrdAvailable;
    }

    // ── Native JNI bindings ─────────────────────────────────────────────────

    /**
     * Initializes an NRD instance.
     * 
     * @param width Screen width
     * @param height Screen height
     * @param maxFramesToAccumulate Max temporal history frames
     * @return Opaque handle to the native NRD Denoiser object, or 0 if failed
     */
    public static native long createDenoiser(int width, int height, int maxFramesToAccumulate);

    /**
     * Set common settings, like camera matrices and jitter configuration.
     * Note: Pointers are passed as longs.
     */
    public static native void setCommonSettings(long denoiserHandle, float[] viewMatrix, float[] projMatrix, 
                                                float cameraPosX, float cameraPosY, float cameraPosZ);

    /**
     * Dispatch the ReBLUR/ReLAX denoiser execution.
     * 
     * @param denoiserHandle The native handle returned by createDenoiser
     * @param inColor Hit distance + color texture address
     * @param inNormal View-space normal texture address
     * @param inMotion Motion vector (screen velocity) texture address
     * @param inDepth Depth buffer address
     * @param outColor Output texture address
     * @return true if successful
     */
    public static native boolean denoise(long denoiserHandle, long inColor, long inNormal, 
                                          long inMotion, long inDepth, long outColor);

    /**
     * Release resources held by the NRD denoiser instance.
     */
    public static native void destroyDenoiser(long denoiserHandle);
}
