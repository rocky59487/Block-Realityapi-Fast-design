package com.blockreality.api.physics.fluid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle façade for the native fluid solver. Parallel to
 * {@link com.blockreality.api.physics.pfsf.NativePFSFRuntime}:
 * default-OFF safety posture, activated by
 * {@code -Dblockreality.native.fluid=true}.
 *
 * <p><b>Preview status (v0.4):</b> this runtime allocates and destroys a
 * native fluid handle but the production fluid tick (island registration,
 * per-tick solve, coupling) still runs on {@link FluidGPUEngine}.
 * Enabling the flag lets operators verify that {@code blockreality_fluid}
 * loads and initialises cleanly on their hardware ahead of the v0.5 tick
 * wiring. {@link #isActive()} therefore stays {@code false} even when the
 * handle is allocated — SPI clients that gate on activation keep using
 * the Java path.</p>
 *
 * <p>When inactive the Java {@link FluidGPUEngine} path handles everything
 * transparently.</p>
 */
public final class NativeFluidRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fluid-NativeRT");

    public static final String ACTIVATION_PROPERTY = "blockreality.native.fluid";

    private static final boolean       FLAG_ENABLED   = Boolean.getBoolean(ACTIVATION_PROPERTY);
    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long    handle      = 0L;
    /**
     * PR#187 capy-ai R34: until the production fluid tick routes through
     * this runtime, {@link #isActive()} is pinned to {@code false} even
     * when the native handle allocates successfully — otherwise SPI
     * clients that gate on activation would see {@code true} while
     * {@link FluidGPUEngine} continues to drive the tick. When the tick
     * wiring lands (v0.5+), flip this flag on a successful {@code init}.
     */
    private static volatile boolean active      = false;
    private static volatile boolean initialized = false;

    private NativeFluidRuntime() {}

    public static boolean isActive()       { return active; }
    /** @return {@code true} once the native handle has been allocated, regardless
     *          of whether the production fluid tick is routed through it. */
    public static boolean isInitialized()  { return initialized; }
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
            handle      = h;
            initialized = true;
            // active stays false — production fluid tick is not yet routed
            // through this handle. See class javadoc (R34 preview note).
            LOGGER.info("Native fluid runtime INITIALIZED (preview; tick path not yet "
                    + "routed) — blockreality_fluid v{} (handle=0x{})",
                    NativeFluidBridge.getVersion(), Long.toHexString(h));
        } catch (Throwable t) {
            LOGGER.error("Native fluid init threw: {}. Falling back.", t.toString(), t);
            if (h != 0L) { try { NativeFluidBridge.nativeDestroy(h); } catch (Throwable ignored) {} }
            active = false; handle = 0L;
        }
    }

    public static synchronized void shutdown() {
        long h = handle;
        handle = 0L; active = false; initialized = false;
        // Reset so the next init() in the same JVM can re-create the handle.
        INIT_ATTEMPTED.set(false);
        if (h == 0L) return;
        try { NativeFluidBridge.nativeDestroy(h); } catch (Throwable t) {
            LOGGER.warn("fluid_destroy threw: {}", t.toString());
        }
    }

    public static String getStatus() {
        if (active)                          return "Native Fluid: ACTIVE v" + NativeFluidBridge.getVersion();
        if (initialized)                     return "Native Fluid: INITIALIZED (preview) v" + NativeFluidBridge.getVersion();
        if (!FLAG_ENABLED)                   return "Native Fluid: DISABLED (flag off)";
        if (!NativeFluidBridge.isAvailable()) return "Native Fluid: LIBRARY MISSING";
        return "Native Fluid: INIT FAILED";
    }
}
