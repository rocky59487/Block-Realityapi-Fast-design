package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Façade + {@link IPFSFRuntime} adapter for the native {@code libblockreality_pfsf}
 * runtime.
 *
 * <p>The class keeps its original static lifecycle surface (unchanged from
 * v0.3c Phase 1) so {@link PFSFEngine#init()} can continue to bring up the
 * native handle side-by-side with the Java {@link PFSFEngineInstance}. On
 * top of that, a singleton {@link RuntimeView} implements the existing
 * {@link IPFSFRuntime} Strategy interface so future milestones can swap the
 * active solver without changing a single call site in {@link PFSFEngine}.</p>
 *
 * <p>Routing gate — {@link #KERNELS_PORTED}: the view advertises
 * {@link IPFSFRuntime#isAvailable()} = {@code true} only when both
 * <ul>
 *   <li>{@link #isActive()} (flag on, library loaded, init succeeded), and</li>
 *   <li>{@link #KERNELS_PORTED} (ABI + solver kernels in place — flipped by
 *       M2b once RBGS/PCG/MG/failure/phase_field are live in libpfsf).</li>
 * </ul>
 * Until M2b, {@link PFSFEngine#getRuntime()} transparently returns the Java
 * engine so there's no regression when operators enable
 * {@code -Dblockreality.native.pfsf=true}.</p>
 */
public final class NativePFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-NativeRT");

    /** Activation flag. Read once; changes require restart. */
    public static final String ACTIVATION_PROPERTY = "blockreality.native.pfsf";

    /**
     * M2 → M2b gate. Flips to {@code true} once the native solver ports the
     * RBGS/PCG/MG/failure/phase-field kernels and {@code pfsf_tick_dbb}
     * produces the same results as the Java path within 1 ULP. While
     * {@code false}, {@link #asRuntime()} reports not-available so
     * {@link PFSFEngine#getRuntime()} falls back to Java.
     *
     * <p>Do NOT flip this by hand — it's toggled by a CI job that compares
     * {@code stress.bin} dumps produced by {@code pfsf_cli} against the Java
     * reference on the 20-island stress fixture.</p>
     */
    private static final boolean KERNELS_PORTED = false;

    private static final boolean       FLAG_ENABLED   = Boolean.getBoolean(ACTIVATION_PROPERTY);
    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long    handle = 0L;
    private static volatile boolean active = false;

    /** IPFSFRuntime singleton — set once; safe to publish. */
    private static final RuntimeView VIEW = new RuntimeView();

    private NativePFSFRuntime() {}

    // ═══════════════════════════════════════════════════════════════
    //  Activation gate
    // ═══════════════════════════════════════════════════════════════

    /** @return flag on + library loaded + init succeeded. */
    public static boolean isActive()        { return active;      }
    public static boolean isFlagEnabled()   { return FLAG_ENABLED; }
    public static boolean isLibraryLoaded() { return NativePFSFBridge.isAvailable(); }
    public static boolean areKernelsPorted(){ return KERNELS_PORTED; }

    /**
     * @return the {@link IPFSFRuntime} adapter view. Always returns the same
     *         singleton — callers must query {@link IPFSFRuntime#isAvailable()}
     *         to decide whether to route through it or fall back to the Java
     *         engine. Equivalent to asking "is native ready end-to-end?".
     */
    public static IPFSFRuntime asRuntime() { return VIEW; }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle (static, called from PFSFEngine)
    // ═══════════════════════════════════════════════════════════════

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
            LOGGER.info("Native PFSF runtime attached — libblockreality_pfsf v{} (handle=0x{}, kernels_ported={})",
                    NativePFSFBridge.getVersion(), Long.toHexString(h), KERNELS_PORTED);
            if (!KERNELS_PORTED) {
                LOGGER.info("Native PFSF solver kernels not yet ported (M2b) — Java path remains authoritative.");
            }
        } catch (Throwable t) {
            LOGGER.error("Native PFSF init threw: {}. Falling back to Java solver.", t.toString(), t);
            if (h != 0L) {
                try { NativePFSFBridge.nativeDestroy(h); } catch (Throwable ignored) {}
            }
            active = false;
            handle = 0L;
        }
    }

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

    public static String getStatus() {
        if (active) {
            return String.format("Native PFSF: %s | lib=%s | handle=0x%x",
                    KERNELS_PORTED ? "ROUTING" : "ATTACHED (kernels not ported)",
                    NativePFSFBridge.getVersion(), handle);
        }
        if (!FLAG_ENABLED)                      return "Native PFSF: DISABLED (flag off)";
        if (!NativePFSFBridge.isAvailable())    return "Native PFSF: LIBRARY MISSING";
        return "Native PFSF: INIT FAILED";
    }

    public static long getHandle() { return handle; }

    // ═══════════════════════════════════════════════════════════════
    //  Sparse voxel re-upload helpers (v0.3c M2n)
    // ═══════════════════════════════════════════════════════════════
    //
    // Parity surface for {@link PFSFSparseUpdate}. Java code either writes
    // into the returned DBB directly (native path) or keeps using its own
    // VulkanComputeContext staging buffer (Java path). The parity harness
    // flips between the two backends and asserts the post-scatter phi/
    // source/cond arrays match within tolerance.

    /**
     * Returns a DirectByteBuffer aliased to the island's native sparse
     * upload SSBO, or {@code null} when the native runtime isn't attached.
     * The buffer is allocated lazily on first call and freed when the
     * island is removed — callers MUST NOT free it themselves.
     */
    public static ByteBuffer getSparseUploadBuffer(int islandId) {
        if (!active) return null;
        try {
            return NativePFSFBridge.nativeGetSparseUploadBuffer(handle, islandId);
        } catch (Throwable t) {
            LOGGER.warn("nativeGetSparseUploadBuffer threw: {}", t.toString());
            return null;
        }
    }

    /**
     * Dispatches the native sparse-scatter pipeline for {@code updateCount}
     * records already packed into the buffer returned by
     * {@link #getSparseUploadBuffer}. Returns the native result code
     * ({@link NativePFSFBridge.PFSFResult#OK} on success) or
     * {@link NativePFSFBridge.PFSFResult#ERROR_NOT_INIT} when inactive.
     */
    public static int notifySparseUpdates(int islandId, int updateCount) {
        if (!active) return NativePFSFBridge.PFSFResult.ERROR_NOT_INIT;
        try {
            return NativePFSFBridge.nativeNotifySparseUpdates(handle, islandId, updateCount);
        } catch (Throwable t) {
            LOGGER.warn("nativeNotifySparseUpdates threw: {}", t.toString());
            return NativePFSFBridge.PFSFResult.ERROR_VULKAN;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IPFSFRuntime Strategy adapter
    // ═══════════════════════════════════════════════════════════════
    //
    // The view lives on top of the static handle/active state above. It
    // does NOT carry its own state; each IPFSFRuntime method translates
    // directly to a NativePFSFBridge call. Call sites that route through
    // this adapter MUST first check isAvailable() — when false, the
    // adapter returns benign no-ops so a misconfigured boot cannot drive
    // the simulation into an inconsistent state.

    /** Package-private so {@link PFSFEngine} can route diagnostics to it. */
    static final class RuntimeView implements IPFSFRuntime {

        private RuntimeView() {}

        @Override
        public void init() {
            // Lifecycle is owned by the static NativePFSFRuntime.init() which
            // is invoked from PFSFEngine.init() before the Java engine — the
            // view only surfaces the existing handle.
            NativePFSFRuntime.init();
        }

        @Override
        public void shutdown() { NativePFSFRuntime.shutdown(); }

        @Override
        public boolean isAvailable() {
            // Strategy routing gate — both the handle must be live AND the
            // kernels must be ported for call sites to route through here.
            return active && KERNELS_PORTED;
        }

        @Override
        public String getStats() { return getStatus(); }

        @Override
        public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
            if (!isAvailable()) return;
            // M2b: Java side will refresh dirty DBBs via PFSFDataBuilder then
            // call NativePFSFBridge.nativeTickDbb(handle, dirty, epoch, failureBuf).
            // Intentionally unimplemented until the kernels land — fallback
            // (via PFSFEngine.getRuntime() picking the Java instance) covers
            // this window.
            LOGGER.debug("NativePFSFRuntime.onServerTick() reached while KERNELS_PORTED=false — no-op.");
        }

        @Override
        public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial, Set<BlockPos> anchors) {
            // Sparse notification is safe to forward even before kernel port —
            // the native side just records the dirty flag. But guard on handle
            // presence to keep the invariant "no JNI call without an active handle".
            if (!active) return;
            // Voxel coords are island-local; the Java DataBuilder owns that
            // mapping, so the notification is forwarded as-is.
            NativePFSFBridge.nativeMarkFullRebuild(handle, islandId);
        }

        @Override public void setMaterialLookup(Function<BlockPos, RMaterial> lookup)  { /* M2b: DBB push */ }
        @Override public void setAnchorLookup(Function<BlockPos, Boolean> lookup)      { /* M2b: DBB push */ }
        @Override public void setFillRatioLookup(Function<BlockPos, Float> lookup)     { /* M2b: DBB push */ }
        @Override public void setCuringLookup(Function<BlockPos, Float> lookup)        { /* M2b: DBB push */ }

        @Override
        public void setWindVector(Vec3 wind) {
            if (!active || wind == null) return;
            NativePFSFBridge.nativeSetWind(handle, (float) wind.x, (float) wind.y, (float) wind.z);
        }

        @Override
        public void removeBuffer(int islandId) {
            if (!active) return;
            NativePFSFBridge.nativeRemoveIsland(handle, islandId);
        }
    }
}
