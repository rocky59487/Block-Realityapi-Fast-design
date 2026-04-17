package com.blockreality.api.physics.fluid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle façade for the native fluid solver. Parallel to
 * {@link com.blockreality.api.physics.pfsf.NativePFSFRuntime}:
 * default-OFF safety posture, activated by
 * {@code -Dblockreality.native.fluid=true}. When inactive the Java
 * {@link FluidGPUEngine} path handles everything transparently.
 */
public final class NativeFluidRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fluid-NativeRT");

    public static final String ACTIVATION_PROPERTY = "blockreality.native.fluid";

    private static final boolean       FLAG_ENABLED   = Boolean.getBoolean(ACTIVATION_PROPERTY);
    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long    handle = 0L;
    private static volatile boolean active = false;

    private NativeFluidRuntime() {}

    public static boolean isActive()       { return active; }
    public static boolean isFlagEnabled()  { return FLAG_ENABLED; }
    public static boolean isLibraryLoaded(){ return NativeFluidBridge.isAvailable(); }
    public static long    getHandle()      { return handle; }

    public static synchronized void init() {
        if (!INIT_ATTEMPTED.compareAndSet(false, true)) return;
        if (!FLAG_ENABLED) {
            LOGGER.debug("Native fluid runtime disabled: -D{} is not set.", ACTIVATION_PROPERTY);
            return;
        }
        if (!NativeFluidBridge.isAvailable()) {
            LOGGER.warn("Native fluid runtime requested (-D{}=true) but blockreality_fluid "
                    + "was not loaded. Falling back to Java fluid.", ACTIVATION_PROPERTY);
            return;
        }

        long h = 0L;
        try {
            h = NativeFluidBridge.nativeCreate(
                    /* maxIslandSize         */ 50_000,
                    /* tickBudgetMs          */ 4,
                    /* vramBudgetBytes       */ 128L * 1024 * 1024,
                    /* enableSurfaceTension  */ true,
                    /* enableCoupling        */ true);
            if (h == 0L) {
                LOGGER.warn("fluid_create() returned null. Falling back.");
                return;
            }
            int rc = NativeFluidBridge.nativeInit(h);
            if (rc != NativeFluidBridge.Result.OK) {
                LOGGER.warn("fluid_init() failed: rc={}. Falling back.", rc);
                NativeFluidBridge.nativeDestroy(h);
                return;
            }
            handle = h;
            active = true;
            LOGGER.info("Native fluid runtime active — blockreality_fluid v{} (handle=0x{})",
                    NativeFluidBridge.getVersion(), Long.toHexString(h));
        } catch (Throwable t) {
            LOGGER.error("Native fluid init threw: {}. Falling back.", t.toString(), t);
            if (h != 0L) { try { NativeFluidBridge.nativeDestroy(h); } catch (Throwable ignored) {} }
            active = false; handle = 0L;
        }
    }

    public static synchronized void shutdown() {
        long h = handle;
        if (h == 0L) return;
        handle = 0L; active = false;
        try { NativeFluidBridge.nativeDestroy(h); } catch (Throwable t) {
            LOGGER.warn("fluid_destroy threw: {}", t.toString());
        }
    }

    public static String getStatus() {
        if (active)                          return "Native Fluid: ACTIVE v" + NativeFluidBridge.getVersion();
        if (!FLAG_ENABLED)                   return "Native Fluid: DISABLED (flag off)";
        if (!NativeFluidBridge.isAvailable()) return "Native Fluid: LIBRARY MISSING";
        return "Native Fluid: INIT FAILED";
    }
}
