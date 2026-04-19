package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.3d Phase 5 + v0.4 M2 — SPI augmentation host.
 *
 * <p>External mods plug per-voxel DirectByteBuffer slots into the PFSF
 * solver via this facade. Each slot corresponds to one SPI manager
 * (thermal field, fluid pressure, cable tension, …); the native
 * {@code libpfsf_compute} stores them in a process-wide registry keyed
 * by {@code (islandId, kind)} so the plan dispatcher can pick them
 * up at the appropriate stage boundary without round-tripping through
 * Java.</p>
 *
 * <h2>v0.4 M2 additions</h2>
 * <ul>
 *   <li>{@link #publish(int, int, ByteBuffer, int)} — caller no longer
 *       tracks version counters; the host assigns one per (island, kind)
 *       and bumps on every call so the native side knows to re-read the
 *       DBB content.</li>
 *   <li>Strong-reference map keeps every published DBB alive until
 *       {@link #clear(int, int)} (or
 *       {@link #clearIsland(int)}) is called. Prevents the GC from
 *       reclaiming a buffer whose raw address the native registry still
 *       holds — Vulkan would read freed memory on the next tick.</li>
 *   <li>{@link AugBinder} + {@link #registerBinder(AugBinder)} +
 *       {@link #runBinders(int)} wire per-island binders to fire
 *       before plan assembly in
 *       {@link PFSFEngineInstance#tick(long, int)}.</li>
 * </ul>
 *
 * <p>Calling into this host is optional: when {@code compute.v5} isn't
 * available the methods short-circuit to no-ops and the engine falls
 * back to the default behaviour defined by each SPI's pre-v0.3d code
 * path (Java reference). Nothing crashes.</p>
 *
 * <p>Thread-safety: the strong-ref map and binder list are both
 * concurrent. The native side uses a shared_mutex; concurrent
 * publish/clear from different worker threads is safe.</p>
 */
public final class PFSFAugmentationHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Augmentation");

    /** Hot-path binder contract — see {@link #runBinders(int)}. */
    @FunctionalInterface
    public interface AugBinder {
        /**
         * Called once per island per tick. Implementations read their
         * SPI manager state and publish matching DBB slots via
         * {@link PFSFAugmentationHost#publish(int, int, ByteBuffer, int)}.
         * Missing / inactive SPI sources should leave the slot
         * unregistered so the dispatcher treats the opcode as no-op.
         */
        void bind(int islandId);
    }

    /* Pack (islandId, kind) into a single long key so we can use a
     * single ConcurrentHashMap instead of nested maps. islandId in the
     * upper 32 bits, kind in the lower 32 — both fit even at the full
     * int range. */
    private static long key(int islandId, int kind) {
        return (((long) islandId) << 32) | (kind & 0xFFFFFFFFL);
    }

    /* Strong references to every published DBB. Keys outlive the
     * native registry entry so the JVM cannot GC the buffer while
     * plan_dispatcher still holds its raw address. */
    private static final Map<Long, ByteBuffer> STRONG_REFS = new ConcurrentHashMap<>();

    /* Monotonic version counter per (island, kind). Bumped on each
     * publish() call so the native side reliably detects changes —
     * mods never need to thread their own version counter through. */
    private static final Map<Long, AtomicInteger> VERSIONS = new ConcurrentHashMap<>();

    private static final List<AugBinder> BINDERS = new CopyOnWriteArrayList<>();

    /**
     * PR#187 capy-ai R16: per-slot monitor so (publish) and (clear) on the
     * same (island, kind) never interleave their Java-map update and their
     * native register/clear call. Without this a racing
     * {@code clear() → nativeAugClear} can complete AFTER a concurrent
     * {@code publish() → STRONG_REFS.put → nativeAugRegister} finished
     * registering the new DBB on the native side, which leaves the native
     * registry holding a buffer whose strong-ref was already dropped on
     * the Java side — the GC can then free it out from under Vulkan.
     *
     * We keep a bounded monitor set (one entry per live slot) and prune
     * on clear so long-running servers don't accumulate dead monitors.
     */
    private static final Map<Long, Object> SLOT_LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(long key) {
        return SLOT_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    private PFSFAugmentationHost() {}

    /* ═══════════════════════════════════════════════════════════════
     *  Slot publishing
     * ═══════════════════════════════════════════════════════════════ */

    /**
     * Publish or refresh a slot with auto-assigned monotonic version.
     *
     * <p>This is the recommended entry point for binders. The host
     * takes a strong reference to the DBB so the buffer cannot be
     * reclaimed while the native dispatcher holds its raw address.
     * Subsequent calls with the same (islandId, kind) overwrite the
     * previous entry; the old strong reference is dropped atomically.</p>
     *
     * @param islandId   PFSF island identifier
     * @param kind       one of {@link NativePFSFBridge.AugKind}
     * @param dbb        direct byte buffer (native-order)
     * @param strideBytes bytes per voxel entry (4 for float, 12 for float3)
     * @return whether the native registry accepted the slot.
     */
    public static boolean publish(int islandId, int kind, ByteBuffer dbb,
                                    int strideBytes) {
        if (dbb == null || !dbb.isDirect()) {
            LOGGER.warn("[PFSF-Aug] publish ignored — buffer is null or non-direct (island={}, kind={})",
                    islandId, kind);
            return false;
        }

        /* Java-only / native-off builds must observe the advertised
         * "short-circuit to no-op" contract: no state mutation, no
         * side effects, just return false. Check compute.v5 availability
         * BEFORE touching STRONG_REFS or bumping VERSIONS so a Java-only
         * install doesn't accumulate per-island retained DBBs and
         * visible-but-meaningless version counters. */
        if (!NativePFSFBridge.hasComputeV5()) {
            return false;
        }

        /* Retain the DBB ahead of the native call so a concurrent GC
         * cycle between put() and nativeAugRegister() can't free it.
         * PR#187 capy-ai R16: serialize the map update and the native
         * register call against a concurrent clear() on the same slot —
         * otherwise the clear can race in between STRONG_REFS.put and
         * nativeAugRegister, leaving native holding a DBB the Java side
         * no longer retains. */
        final long k = key(islandId, kind);
        synchronized (lockFor(k)) {
            STRONG_REFS.put(k, dbb);
            final int version = VERSIONS
                    .computeIfAbsent(k, kk -> new AtomicInteger())
                    .incrementAndGet();
            try {
                final int rc = NativePFSFBridge.nativeAugRegister(
                        islandId, kind, dbb, strideBytes, version);
                if (rc != NativePFSFBridge.PFSFResult.OK) {
                    STRONG_REFS.remove(k, dbb);
                    LOGGER.debug("[PFSF-Aug] nativeAugRegister returned {} — dropping strong ref", rc);
                    return false;
                }
                return true;
            } catch (UnsatisfiedLinkError e) {
                STRONG_REFS.remove(k, dbb);
                return false;
            }
        }
    }

    /**
     * Legacy Phase-5 API kept for callers that already track their own
     * version counter (compute.v5 smoke tests). New code should prefer
     * {@link #publish(int, int, ByteBuffer, int)}.
     */
    public static boolean register(int islandId, int kind, ByteBuffer dbb,
                                     int strideBytes, int version) {
        if (!NativePFSFBridge.hasComputeV5()) return false;
        if (dbb == null || !dbb.isDirect()) {
            LOGGER.warn("[PFSF-Aug] register ignored — buffer is null or non-direct");
            return false;
        }
        final long k = key(islandId, kind);
        /* PR#187 capy-ai R16: same per-slot lock as publish() so the
         * legacy explicit-version API can't race a clear() on the same
         * (island, kind) pair either. */
        synchronized (lockFor(k)) {
            STRONG_REFS.put(k, dbb);
            /* Sync the auto-counter so a later publish() still bumps monotonically. */
            VERSIONS.computeIfAbsent(k, kk -> new AtomicInteger())
                    .accumulateAndGet(version, Math::max);
            try {
                int result = NativePFSFBridge.nativeAugRegister(islandId, kind, dbb,
                        strideBytes, version);
                if (result != NativePFSFBridge.PFSFResult.OK) {
                    STRONG_REFS.remove(k, dbb);
                    return false;
                }
                return true;
            } catch (UnsatisfiedLinkError e) {
                STRONG_REFS.remove(k, dbb);
                return false;
            }
        }
    }

    /** Clear one slot — no-op when not registered. Drops the strong ref. */
    public static void clear(int islandId, int kind) {
        final long k = key(islandId, kind);
        /* PR#187 capy-ai R16: hold the per-slot lock across the map
         * cleanup AND the native clear, so a concurrent publish() on
         * the same slot completes fully before we drop its DBB. The
         * previous code left a window where publish() could commit a
         * new native registration AFTER we already removed the Java
         * strong ref. */
        synchronized (lockFor(k)) {
            STRONG_REFS.remove(k);
            VERSIONS.remove(k);
            if (NativePFSFBridge.hasComputeV5()) {
                try {
                    NativePFSFBridge.nativeAugClear(islandId, kind);
                } catch (UnsatisfiedLinkError ignored) { }
            }
        }
        /* Prune the lock outside the synchronized block — a racing
         * publish() re-creates the monitor via computeIfAbsent, so even
         * if we remove here and another thread enters on a stale key
         * reference, they'll both observe the same new monitor via
         * the map. */
        SLOT_LOCKS.remove(k);
    }

    /** Clear every slot registered to an island (call on island dispose). */
    public static void clearIsland(int islandId) {
        /* Drop every strong ref whose key's upper 32 bits == islandId. */
        final long prefix = ((long) islandId) << 32;
        final Set<Long> victims = new java.util.HashSet<>();
        for (Long k : STRONG_REFS.keySet()) {
            if ((k & 0xFFFFFFFF00000000L) == prefix) victims.add(k);
        }
        /* PR#187 capy-ai R16: take each slot's lock in turn so a
         * concurrent publish() completes fully before we pull its DBB
         * out of STRONG_REFS. */
        for (Long k : victims) {
            synchronized (lockFor(k)) {
                STRONG_REFS.remove(k);
                VERSIONS.remove(k);
            }
            SLOT_LOCKS.remove(k);
        }
        if (!NativePFSFBridge.hasComputeV5()) return;
        try {
            NativePFSFBridge.nativeAugClearIsland(islandId);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /**
     * Full island teardown: drop the native+strong-ref slots for this
     * island AND release every registered binder's per-island DBB cache.
     * Call from {@link PFSFBufferManager#removeBuffer(int)} so long-running
     * servers don't accumulate unreclaimable direct memory (see PR#187
     * capy-ai R8 — the original implementation left binder caches and
     * STRONG_REFS alive for the JVM lifetime).
     */
    public static void clearIslandFully(int islandId) {
        clearIsland(islandId);
        for (AugBinder binder : BINDERS) {
            if (binder instanceof com.blockreality.api.physics.pfsf.augbind.AbstractAugBinder abs) {
                try {
                    abs.releaseIsland(islandId);
                } catch (Throwable t) {
                    LOGGER.debug("[PFSF-Aug] releaseIsland({}) on {} threw — ignoring",
                            islandId, abs.getClass().getSimpleName(), t);
                }
            }
        }
    }

    /**
     * Drop every slot across every island (process-wide cleanup). Called
     * from {@link PFSFBufferManager#freeAll()} on engine shutdown so
     * no direct memory leaks across re-init cycles.
     */
    public static void clearAllFully() {
        /* Collect island IDs first so we iterate a stable set — clearIsland
         * mutates STRONG_REFS. */
        final java.util.Set<Integer> islands = new java.util.HashSet<>();
        for (Long k : STRONG_REFS.keySet()) {
            islands.add((int) (k >>> 32));
        }
        for (Integer id : islands) {
            clearIslandFully(id);
        }
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

    /**
     * @return Java-side version counter for the (island, kind) slot, or
     *         {@code -1} if the slot was never published. Unlike
     *         {@link #queryVersion}, this does not round-trip through
     *         native — usable on GPU-less CI runners to verify binder
     *         behaviour. The two counters stay in sync on a live runtime
     *         because {@link #publish} bumps the Java side before calling
     *         {@code nativeAugRegister} with the same value.
     */
    public static int queryLocalVersion(int islandId, int kind) {
        final AtomicInteger v = VERSIONS.get(key(islandId, kind));
        return v == null ? -1 : v.get();
    }

    /**
     * @return whether the Java-side strong-ref map holds a DBB for the
     *         slot. Test helper — a published buffer's GC safety is the
     *         invariant this exposes.
     */
    public static boolean hasStrongRef(int islandId, int kind) {
        return STRONG_REFS.containsKey(key(islandId, kind));
    }

    /* ═══════════════════════════════════════════════════════════════
     *  Binder registration + runtime hook
     * ═══════════════════════════════════════════════════════════════ */

    /**
     * Register a binder that will be invoked for every island before
     * plan assembly. Binders run in registration order; each one is
     * expected to read its SPI manager state and call
     * {@link #publish(int, int, ByteBuffer, int)} for whichever
     * (island, kind) pairs it owns.
     *
     * <p>Duplicate registration is silently ignored so module reloads
     * cannot accumulate phantom binders.</p>
     */
    public static void registerBinder(AugBinder binder) {
        if (binder == null) return;
        if (!BINDERS.contains(binder)) {
            BINDERS.add(binder);
        }
    }

    /** Deregister a binder; e.g. on module teardown. */
    public static void unregisterBinder(AugBinder binder) {
        if (binder != null) BINDERS.remove(binder);
    }

    /** @return snapshot of registered binders — for test assertions. */
    public static List<AugBinder> registeredBinders() {
        return Collections.unmodifiableList(BINDERS);
    }

    /**
     * Fire every registered binder for the given island. Called from
     * {@link PFSFEngineInstance#tick(long, int)} just before the tick
     * plan is assembled so fresh SPI contributions are visible to the
     * dispatcher's OP_AUG_* opcodes.
     *
     * <p>Each binder is isolated behind a try/catch so a single broken
     * SPI cannot break the whole tick. Failures are logged at debug to
     * keep production logs quiet.</p>
     */
    public static void runBinders(int islandId) {
        for (AugBinder binder : BINDERS) {
            try {
                binder.bind(islandId);
            } catch (Throwable t) {
                LOGGER.debug("[PFSF-Aug] binder {} threw for island {} — ignoring",
                        binder.getClass().getSimpleName(), islandId, t);
            }
        }
    }

    /* ═══════════════════════════════════════════════════════════════
     *  Test-only hooks
     * ═══════════════════════════════════════════════════════════════ */

    /** @return number of DBBs currently strongly retained (all islands). */
    public static int strongRefCount() {
        return STRONG_REFS.size();
    }

    /** Reset all host state — used by unit tests to isolate fixtures. */
    public static void resetForTesting() {
        STRONG_REFS.clear();
        VERSIONS.clear();
        BINDERS.clear();
    }
}
