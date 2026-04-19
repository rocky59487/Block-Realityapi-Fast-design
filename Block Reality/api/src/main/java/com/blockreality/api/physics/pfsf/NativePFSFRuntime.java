package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-level façade over {@link NativePFSFBridge}.
 *
 * <p>Owns a single {@code pfsf_engine} handle for the lifetime of the
 * server/integrated client, translates between Java-friendly arguments and
 * the JNI calling convention, and gates activation behind the
 * {@code -Dblockreality.native.pfsf} system property (v0.3c Phase 1
 * safety posture — default OFF).</p>
 *
 * <p>This class is intentionally lifecycle-only for Phase 1. Tick routing
 * into the native solver will be wired up in Phase 1b together with the
 * JNI callback trampolines; for now, {@link #isActive()} is authoritative
 * and every Java call-site that respects it must fall back to the existing
 * Java path when it returns {@code false}.</p>
 */
public final class NativePFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-NativeRT");

    /** Activation flag. Read once; changes require restart. */
    public static final String ACTIVATION_PROPERTY = "blockreality.native.pfsf";

    private static final boolean FLAG_ENABLED = Boolean.getBoolean(ACTIVATION_PROPERTY);

    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long handle = 0L;
    private static volatile boolean active = false;

    private NativePFSFRuntime() {}

    // ═══════════════════════════════════════════════════════════════
    //  Activation gate
    // ═══════════════════════════════════════════════════════════════

    /**
     * @return whether callers should delegate to the native runtime this session.
     *         Equivalent to {@code flagEnabled && libraryLoaded && initSucceeded}.
     */
    public static boolean isActive() {
        return active;
    }

    /** Diagnostic breakdown — whether the {@code -D} flag was set. */
    public static boolean isFlagEnabled() {
        return FLAG_ENABLED;
    }

    /** Diagnostic breakdown — whether the shared library is on the host. */
    public static boolean isLibraryLoaded() {
        return NativePFSFBridge.isAvailable();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initialises the native engine iff the activation flag is set and the
     * library loaded. Idempotent; safe to call multiple times. Failures
     * flip the runtime into the "inactive" state so callers fall back to
     * the Java path silently.
     */
    public static synchronized void init() {
        if (!INIT_ATTEMPTED.compareAndSet(false, true)) return;

        if (!FLAG_ENABLED) {
            LOGGER.debug("Native PFSF runtime disabled: -D{} is not set.", ACTIVATION_PROPERTY);
            return;
        }
        if (!NativePFSFBridge.isAvailable()) {
            LOGGER.warn("Native PFSF runtime requested (-D{}=true) but libblockreality_pfsf "
                    + "was not loaded. Falling back to Java solver.", ACTIVATION_PROPERTY);
            return;
        }

        long h = 0L;
        try {
            h = NativePFSFBridge.nativeCreate(
                    /* maxIslandSize    */ 50_000,
                    /* tickBudgetMs     */ Math.max(1, BRConfig.getPFSFTickBudgetMs()),
                    /* vramBudgetBytes  */ 512L * 1024 * 1024,
                    /* enablePhaseField */ true,
                    /* enableMultigrid  */ true);
            if (h == 0L) {
                LOGGER.warn("pfsf_create() returned null. Falling back to Java solver.");
                return;
            }

            int rc = NativePFSFBridge.nativeInit(h);
            if (rc != NativePFSFBridge.PFSFResult.OK) {
                LOGGER.warn("pfsf_init() failed: {}. Falling back to Java solver.",
                        NativePFSFBridge.PFSFResult.describe(rc));
                NativePFSFBridge.nativeDestroy(h);
                return;
            }

            handle = h;
            active = true;
            LOGGER.info("Native PFSF runtime active — libblockreality_pfsf v{} (handle=0x{})",
                    NativePFSFBridge.getVersion(), Long.toHexString(h));
        } catch (Throwable t) {
            LOGGER.error("Native PFSF init threw: {}. Falling back to Java solver.", t.toString(), t);
            if (h != 0L) {
                try { NativePFSFBridge.nativeDestroy(h); } catch (Throwable ignored) {}
            }
            active = false;
            handle = 0L;
        }
    }

    /** Releases the engine handle. Safe to call multiple times. */
    public static synchronized void shutdown() {
        long h = handle;
        if (h == 0L) return;
        handle = 0L;
        active = false;
        try {
            NativePFSFBridge.nativeDestroy(h);
            LOGGER.info("Native PFSF runtime shut down.");
        } catch (Throwable t) {
            LOGGER.warn("nativeDestroy threw during shutdown: {}", t.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Diagnostics
    // ═══════════════════════════════════════════════════════════════

    /**
     * @return a human-readable status line for {@code /br vulkan_test} and
     *         crash reports; never throws.
     */
    public static String getStatus() {
        if (active) {
            return String.format("Native PFSF: ACTIVE | lib=%s | handle=0x%x",
                    NativePFSFBridge.getVersion(), handle);
        }
        if (!FLAG_ENABLED) return "Native PFSF: DISABLED (flag off)";
        if (!NativePFSFBridge.isAvailable()) return "Native PFSF: LIBRARY MISSING";
        return "Native PFSF: INIT FAILED";
    }

    /** @return engine handle (opaque) — {@code 0} when inactive. */
    public static long getHandle() {
        return handle;
    }
}
