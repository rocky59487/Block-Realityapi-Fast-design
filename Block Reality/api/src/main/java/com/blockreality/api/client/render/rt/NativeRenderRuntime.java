package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle façade for the native Vulkan RT runtime. Default-OFF safety
 * posture; activate with {@code -Dblockreality.native.render=true}.
 *
 * <p><b>Preview status (v0.4):</b> this runtime allocates and destroys a
 * native render handle but the production render loop (BRVulkanRT camera
 * updates, frame submission, BRVulkanBVH builds) is still routed through
 * the Java/LWJGL path. Enabling the flag is therefore a <i>no-op from the
 * user's perspective</i> — it exists so operators can verify that
 * {@code blockreality_render} loads, allocates, and tears down cleanly
 * on their hardware ahead of the v0.5 frame-path wiring. The bridge and
 * lifecycle stay under test in the interim. {@link #isActive()} returns
 * {@code false} even when the handle is allocated, so SPI clients that
 * gate on activation keep using the Java path.</p>
 *
 * <p>When inactive, {@link BRVulkanRT}, {@link BRVulkanBVH}, and the
 * denoiser/dispatcher classes continue running entirely in Java via
 * LWJGL — same as every previous Block Reality release.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NativeRenderRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Render-NativeRT");

    public static final String ACTIVATION_PROPERTY = "blockreality.native.render";

    private static final boolean       FLAG_ENABLED   = Boolean.getBoolean(ACTIVATION_PROPERTY);
    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long    handle      = 0L;
    /**
     * PR#187 capy-ai R33: until the production render loop routes through
     * this runtime, {@link #isActive()} is pinned to {@code false} even
     * when the native handle allocates successfully — otherwise SPI
     * clients that gate on activation would see {@code true} while the
     * Java path continues to drive the frame. When the frame-path
     * wiring lands (v0.5+), flip this flag on a successful {@code init}.
     */
    private static volatile boolean active      = false;
    private static volatile boolean initialized = false;
    private static volatile int     tier        = NativeRenderBridge.Tier.FALLBACK;

    private NativeRenderRuntime() {}

    public static boolean isActive()        { return active; }
    /** @return {@code true} once the native handle has been allocated, regardless
     *          of whether the production frame path is routed through it. */
    public static boolean isInitialized()   { return initialized; }
    public static boolean isFlagEnabled()   { return FLAG_ENABLED; }
    public static boolean isLibraryLoaded() { return NativeRenderBridge.isAvailable(); }
    public static long    getHandle()       { return handle; }
    public static int     getActiveTier()   { return tier; }

    public static synchronized void init(int width, int height) {
        if (!INIT_ATTEMPTED.compareAndSet(false, true)) return;
        if (!FLAG_ENABLED) {
            LOGGER.debug("Native render runtime disabled: -D{} is not set.", ACTIVATION_PROPERTY);
            return;
        }
        if (!NativeRenderBridge.isAvailable()) {
            LOGGER.warn("Native render runtime requested (-D{}=true) but blockreality_render "
                    + "was not loaded. Falling back to Java path.", ACTIVATION_PROPERTY);
            return;
        }

        long h = 0L;
        try {
            h = NativeRenderBridge.nativeCreate(
                    Math.max(1, width),
                    Math.max(1, height),
                    /* vramBudgetBytes */ 1024L * 1024 * 1024,
                    /* tierOverride    */ NativeRenderBridge.Tier.FALLBACK,
                    /* enableRestir    */ true,
                    /* enableDdgi      */ true,
                    /* enableRelax     */ true);
            if (h == 0L) {
                LOGGER.warn("render_create() returned null. Falling back.");
                return;
            }
            int rc = NativeRenderBridge.nativeInit(h);
            if (rc != NativeRenderBridge.Result.OK) {
                LOGGER.warn("render_init() failed: rc={}. Falling back.", rc);
                NativeRenderBridge.nativeDestroy(h);
                return;
            }
            handle      = h;
            initialized = true;
            // active stays false — production render loop is not yet routed
            // through this handle. See class javadoc (R33 preview note).
            tier        = NativeRenderBridge.nativeActiveTier(h);
            LOGGER.info("Native render runtime INITIALIZED (preview; frame path not yet "
                    + "routed) — tier={} (handle=0x{})",
                    tierName(tier), Long.toHexString(h));
        } catch (Throwable t) {
            LOGGER.error("Native render init threw: {}. Falling back.", t.toString(), t);
            if (h != 0L) { try { NativeRenderBridge.nativeDestroy(h); } catch (Throwable ignored) {} }
            active = false; handle = 0L;
        }
    }

    public static synchronized void shutdown() {
        long h = handle;
        handle = 0L; active = false; initialized = false;
        // Reset so the next init() in the same JVM can re-create the handle.
        INIT_ATTEMPTED.set(false);
        if (h == 0L) return;
        try { NativeRenderBridge.nativeDestroy(h); } catch (Throwable t) {
            LOGGER.warn("render_destroy threw: {}", t.toString());
        }
    }

    public static String getStatus() {
        if (active)                             return "Native Render: ACTIVE tier=" + tierName(tier);
        if (initialized)                        return "Native Render: INITIALIZED (preview) tier=" + tierName(tier);
        if (!FLAG_ENABLED)                      return "Native Render: DISABLED (flag off)";
        if (!NativeRenderBridge.isAvailable())  return "Native Render: LIBRARY MISSING";
        return "Native Render: INIT FAILED";
    }

    private static String tierName(int t) {
        return switch (t) {
            case NativeRenderBridge.Tier.BLACKWELL -> "BLACKWELL";
            case NativeRenderBridge.Tier.ADA       -> "ADA";
            default                                -> "FALLBACK";
        };
    }
}
