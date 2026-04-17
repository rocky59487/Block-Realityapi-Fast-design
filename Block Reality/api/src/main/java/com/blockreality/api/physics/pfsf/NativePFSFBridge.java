package com.blockreality.api.physics.pfsf;

import java.nio.ByteBuffer;

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
            version = nativeVersion();
            loaded  = true;
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

    // ── v0.3c — DirectByteBuffer zero-copy path ─────────────────────────────
    //
    // Island registration hands the C++ side persistent addresses for the
    // bulk voxel arrays (phi, source, conductivity[6N SoA], type, rcomp,
    // rtens) and the world-state lookup tables (materialId, anchorBitmap,
    // fluidPressure, curing). After registration, ticks can run with zero
    // per-voxel JNI traffic — Java refreshes only dirty voxel ranges in
    // place, C++ reads them directly on each tick.
    //
    // All ByteBuffers MUST be direct and 256-byte aligned. On the Java
    // side, {@code MemoryUtil.memAlignedAlloc(256, size)} produces a
    // suitable buffer; {@code XxxIslandBuffer.close()} is responsible for
    // {@code memAlignedFree}. Mis-sized or non-direct buffers cause the
    // registration call to return {@link PFSFResult#ERROR_INVALID_ARG}.

    /**
     * Registers the six primary voxel storage buffers for an island.
     *
     * @param phi           float32 × N                potential field
     * @param source        float32 × N                normalised source term
     * @param conductivity  float32 × 6N (SoA)         per-direction σ
     * @param voxelType     int32 × N                  packed voxel kind
     * @param rcomp         float32 × N                normalised compression limit
     * @param rtens         float32 × N                normalised tension limit
     * @return a {@link PFSFResult} code.
     */
    public static native int nativeRegisterIslandBuffers(long handle,
                                                          int islandId,
                                                          ByteBuffer phi,
                                                          ByteBuffer source,
                                                          ByteBuffer conductivity,
                                                          ByteBuffer voxelType,
                                                          ByteBuffer rcomp,
                                                          ByteBuffer rtens);

    /**
     * Registers the four world-state lookup buffers. Java refreshes only
     * dirty voxels each tick (see {@link PFSFDataBuilder}) — C++ reads
     * them without any JNI callback.
     *
     * @param materialId     int32  × N
     * @param anchorBitmap   int64  × N  (bit i = anchored in world direction i)
     * @param fluidPressure  float32 × N
     * @param curing         float32 × N  (0.0 = fresh, 1.0 = fully cured)
     */
    public static native int nativeRegisterIslandLookups(long handle,
                                                          int islandId,
                                                          ByteBuffer materialId,
                                                          ByteBuffer anchorBitmap,
                                                          ByteBuffer fluidPressure,
                                                          ByteBuffer curing);

    /**
     * Registers the stress readback buffer. The native runtime writes the
     * per-voxel stress utilisation (σ / σmax) here at the end of each tick;
     * Java reads it back without round-tripping.
     *
     * @param stress  float32 × N
     */
    public static native int nativeRegisterStressReadback(long handle,
                                                           int islandId,
                                                           ByteBuffer stress);

    /**
     * Ticks every registered island using the pre-registered buffers.
     * Java must have refreshed the dirty voxel ranges in the DBBs before
     * calling this — C++ then runs the solver entirely GPU-side with no
     * per-voxel JNI traffic.
     *
     * @param failureBuffer  optional int32 DBB sized as
     *                       {@code 4 + 4 * maxFailures} (header + packed
     *                       {@code x,y,z,failureType} tuples). {@code null}
     *                       skips failure reporting for this tick.
     * @return {@link PFSFResult} code.
     */
    public static native int nativeTickDbb(long handle,
                                            int[] dirtyIslandIds,
                                            long currentEpoch,
                                            ByteBuffer failureBuffer);

    /**
     * Drains pending native→Java callback events. Called once per server
     * tick boundary from the main thread. The on-wire format is:
     * {@code int[3 * count]} with {@code {kind, islandId, payloadLo}}
     * tuples (payloadHi is reserved for future 64-bit payloads).
     *
     * @return number of events drained, or 0 if none.
     */
    public static native int nativeDrainCallbacks(long handle, int[] outEvents);

    // ── v0.3c M2n — Sparse voxel re-upload (tick-time scatter) ──────────────
    //
    // Parity with {@link PFSFSparseUpdate}: Java packs up to 512
    // {@code VoxelUpdate} records (48 bytes each) into a persistent-mapped
    // upload SSBO, then asks the native runtime to scatter them into the
    // device-local arrays via {@code sparse_scatter.comp}.

    /**
     * Returns a DirectByteBuffer aliased to the island's VMA-owned sparse
     * upload SSBO. The buffer is allocated lazily on first call (capacity
     * = {@code MAX_SPARSE_UPDATES_PER_TICK × 48 = 24576 bytes}).
     *
     * <p><b>Java MUST NOT free the returned buffer</b> — the backing memory
     * is owned by the native runtime and released when the island is
     * removed or the engine shuts down. Use {@link ByteBuffer#order} to
     * apply the platform's native byte order before writing records.</p>
     *
     * @return the aliased buffer, or {@code null} if the island is unknown
     *         or Vulkan allocation failed.
     */
    public static native ByteBuffer nativeGetSparseUploadBuffer(long handle, int islandId);

    /**
     * Dispatches the sparse-scatter compute pipeline for the given number
     * of records already packed into the buffer returned by
     * {@link #nativeGetSparseUploadBuffer}. {@code updateCount} is clamped
     * to {@code MAX_SPARSE_UPDATES_PER_TICK} on the native side.
     *
     * @return a {@link PFSFResult} code.
     */
    public static native int nativeNotifySparseUpdates(long handle,
                                                        int islandId,
                                                        int updateCount);

    /** libpfsf version string. */
    private static native String nativeVersion();

    // ── v0.3d Phase 1 — ABI / feature probes ────────────────────────────
    //
    // These are static (no engine handle) because they describe the loaded
    // shared library itself, not any particular engine instance. The full
    // feature vocabulary is documented in pfsf_version.h.

    /** Packed (MAJOR<<16)|(MINOR<<8)|PATCH. 0 when compute kernels absent. */
    public static native int nativeAbiVersion();

    public static native boolean nativeHasFeature(String featureName);

    public static native String nativeBuildInfo();

    // ── v0.3d Phase 1 — Stateless compute primitives ───────────────────
    //
    // These call into libpfsf_compute via Get/ReleasePrimitiveArrayCritical
    // for zero-copy array access. Callers MUST check {@link #hasComputeV1}
    // before invoking — an absent library raises UnsatisfiedLinkError,
    // which is caught and converted into a javaRefImpl fallback by the
    // {@code PFSFDataBuilder} / {@code PFSFSourceBuilder} façades.

    /** @see pfsf_compute.h {@code pfsf_wind_pressure_source} */
    public static native float nativeWindPressureSource(float windSpeed,
                                                         float density,
                                                         boolean exposed);

    /** @see pfsf_compute.h {@code pfsf_timoshenko_moment_factor} */
    public static native float nativeTimoshenkoMomentFactor(float sectionWidth,
                                                             float sectionHeight,
                                                             int arm,
                                                             float youngsModulusGPa,
                                                             float poissonRatio);

    /**
     * @see pfsf_compute.h {@code pfsf_normalize_soa6}
     * @return sigmaMax the factor used to normalise. Caller MUST apply the
     *         same factor to any derived arrays it owns (e.g. maxPhi).
     */
    public static native float nativeNormalizeSoA6(float[] source,
                                                    float[] rcomp,
                                                    float[] rtens,
                                                    float[] conductivity,
                                                    float[] hydrationOrNull,
                                                    int n);

    /** @see pfsf_compute.h {@code pfsf_apply_wind_bias} */
    public static native void nativeApplyWindBias(float[] conductivity,
                                                   int n,
                                                   float wx, float wy, float wz,
                                                   float upwindFactor);

    // ── v0.3d Phase 2 — graph/topology primitives ───────────────────────
    //
    // Inputs are flat grids indexed as {@code i = x + lx*(y + ly*z)} — the
    // same layout used by every PFSF SSBO. The Java side currently calls
    // these through helper bridges that linearise a {@code Set<BlockPos>}
    // on the fly; the island-buffer rewrite in a later phase will pass
    // the existing grid-native buffers in directly.

    /**
     * @param members  byte[N] — 1 for island members, 0 elsewhere
     * @param anchors  byte[N] — 1 for anchored members, 0 elsewhere
     * @param outArm   int32[N] — populated with horizontal Manhattan arm
     * @return {@link PFSFResult} code (0 = OK)
     * @see pfsf_compute.h {@code pfsf_compute_arm_map}
     */
    public static native int nativeComputeArmMap(byte[] members, byte[] anchors,
                                                   int lx, int ly, int lz,
                                                   int[] outArm);

    /**
     * @param outArch float[N] — 0.0 for single-sided / unreachable;
     *                ratio in (0,1] for dual-path reachable.
     * @see pfsf_compute.h {@code pfsf_compute_arch_factor_map}
     */
    public static native int nativeComputeArchFactorMap(byte[] members, byte[] anchors,
                                                          int lx, int ly, int lz,
                                                          float[] outArch);

    /**
     * @param conductivity float[6N] SoA — modified in place
     * @param rcomp         float[N]
     * @return number of diagonal slots written
     * @see pfsf_compute.h {@code pfsf_inject_phantom_edges}
     */
    public static native int nativeInjectPhantomEdges(byte[] members,
                                                        float[] conductivity,
                                                        float[] rcomp,
                                                        int lx, int ly, int lz,
                                                        float edgePenalty,
                                                        float cornerPenalty);

    // ── v0.3d Phase 3 — Morton / downsample / tiled layout ──────────────

    /** @see pfsf_compute.h {@code pfsf_morton_encode} */
    public static native int nativeMortonEncode(int x, int y, int z);

    /** @param outXYZ int[3] — filled with (x,y,z). @see pfsf_compute.h {@code pfsf_morton_decode} */
    public static native void nativeMortonDecode(int code, int[] outXYZ);

    /**
     * 2:1 multigrid restrict. coarse dims = ceil(fine/2) on each axis.
     *
     * @param fineType   byte[lxf·lyf·lzf] or {@code null} to skip vote
     * @param coarseType byte[lxc·lyc·lzc] or {@code null}
     * @see pfsf_compute.h {@code pfsf_downsample_2to1}
     */
    public static native int nativeDownsample2to1(float[] fine, byte[] fineType,
                                                    int lxf, int lyf, int lzf,
                                                    float[] coarse, byte[] coarseType);

    /**
     * Re-lay a linear float array into an 8×8×8-tile layout.
     *
     * @param out float[ntx·nty·ntz·512] where nt* = ceil(l*/8)
     * @see pfsf_compute.h {@code pfsf_tiled_layout_build}
     */
    public static native int nativeTiledLayoutBuild(float[] linear,
                                                      int lx, int ly, int lz,
                                                      int tile,
                                                      float[] out);

    // ── v0.3d Phase 4 — diagnostics primitives ──────────────────────────
    //
    // Mirrors PFSFScheduler + IslandFeatureExtractor. Every entry is
    // stateless except {@link #nativeCheckDivergence}, which mutates a
    // caller-owned int[7] state buffer in place (float fields are
    // round-tripped through Float.floatToRawIntBits so we don't need a
    // DirectByteBuffer just for a 28-byte scratch).

    /** @see pfsf_diagnostics.h {@code pfsf_chebyshev_omega} */
    public static native float nativeChebyshevOmega(int iter, float rhoSpec);

    /**
     * Fill {@code out} with the Chebyshev omega schedule.
     *
     * @return number of entries written, or a negative PFSFResult code.
     * @see pfsf_diagnostics.h {@code pfsf_precompute_omega_table}
     */
    public static native int nativePrecomputeOmegaTable(float rhoSpec, float[] out);

    /** @see pfsf_diagnostics.h {@code pfsf_estimate_spectral_radius} */
    public static native float nativeEstimateSpectralRadius(int lMax, float safetyMargin);

    /** @see pfsf_diagnostics.h {@code pfsf_recommend_steps} */
    public static native int nativeRecommendSteps(int ly, int chebyIter,
                                                    boolean isDirty, boolean hasCollapse,
                                                    int stepsMinor, int stepsMajor,
                                                    int stepsCollapse);

    /** @see pfsf_diagnostics.h {@code pfsf_macro_block_active} */
    public static native boolean nativeMacroBlockActive(float residual, boolean wasActive);

    /**
     * @param residuals  per-block residual array
     * @param wasActive  byte-per-block previous state (may be {@code null})
     * @see pfsf_diagnostics.h {@code pfsf_macro_active_ratio}
     */
    public static native float nativeMacroActiveRatio(float[] residuals, byte[] wasActive);

    /**
     * Runs the divergence state machine against a 7-slot int view:
     * {@code [struct_bytes, prev_max_phi_bits, prev_prev_max_phi_bits,
     * oscillation_count, damping_active, chebyshev_iter,
     * prev_max_macro_residual_bits]}. Java must round-trip float fields
     * via {@link Float#floatToRawIntBits} / {@link Float#intBitsToFloat}.
     *
     * @return one of the {@code PFSF_DIV_*} kinds (0 = converging).
     * @see pfsf_diagnostics.h {@code pfsf_check_divergence}
     */
    public static native int nativeCheckDivergence(int[] stateInOut,
                                                     float maxPhiNow,
                                                     float[] macroResiduals,
                                                     float divergenceRatio,
                                                     float dampingSettleThreshold);

    /** @see pfsf_diagnostics.h {@code pfsf_extract_island_features} */
    public static native void nativeExtractIslandFeatures(int lx, int ly, int lz,
                                                            int chebyshevIter,
                                                            float rhoSpecOverride,
                                                            float prevMaxMacroResidual,
                                                            int oscillationCount,
                                                            boolean dampingActive,
                                                            int stableTickCount,
                                                            int lodLevel,
                                                            int lodDormant,
                                                            boolean pcgAllocated,
                                                            float[] macroResiduals,
                                                            float[] out12);

    // ── v0.3d Phase 1 — Java-side feature cache ─────────────────────────
    //
    // {@code nativeHasFeature} involves a JNI string round-trip; cache the
    // Phase-1 "compute.v1" probe so the hot path (e.g. per-voxel
    // Timoshenko) touches one volatile boolean.

    private static volatile Boolean COMPUTE_V1_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes the Phase 1 primitive set
     *         (normalize_soa6 / apply_wind_bias / timoshenko /
     *         wind_pressure). Cached after first successful probe.
     */
    public static boolean hasComputeV1() {
        Boolean cached = COMPUTE_V1_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V1_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v1");
            COMPUTE_V1_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v1 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            // Older native binary without the Phase 1 probe entry.
            COMPUTE_V1_CACHE = Boolean.FALSE;
            return false;
        }
    }

    private static String safeBuildInfo() {
        try {
            String b = nativeBuildInfo();
            return (b != null) ? b : "n/a";
        } catch (UnsatisfiedLinkError e) {
            return "n/a";
        }
    }

    // ── v0.3d Phase 2 — compute.v2 feature probe cache ──────────────────

    private static volatile Boolean COMPUTE_V2_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes the Phase 2 topology
     *         primitives (compute_arm_map / compute_arch_factor_map /
     *         inject_phantom_edges). Cached after first successful probe.
     */
    public static boolean hasComputeV2() {
        Boolean cached = COMPUTE_V2_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V2_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v2");
            COMPUTE_V2_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v2 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            COMPUTE_V2_CACHE = Boolean.FALSE;
            return false;
        }
    }

    // ── v0.3d Phase 3 — compute.v3 feature probe cache ──────────────────

    private static volatile Boolean COMPUTE_V3_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes Phase 3 layout primitives
     *         (morton_encode/decode, downsample_2to1, tiled_layout_build).
     */
    public static boolean hasComputeV3() {
        Boolean cached = COMPUTE_V3_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V3_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v3");
            COMPUTE_V3_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v3 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            COMPUTE_V3_CACHE = Boolean.FALSE;
            return false;
        }
    }

    // ── v0.3d Phase 4 — compute.v4 feature probe cache ──────────────────

    private static volatile Boolean COMPUTE_V4_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes Phase 4 diagnostics
     *         (chebyshev / spectral / recommend / macro / divergence /
     *         island features).
     */
    public static boolean hasComputeV4() {
        Boolean cached = COMPUTE_V4_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V4_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v4");
            COMPUTE_V4_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v4 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            COMPUTE_V4_CACHE = Boolean.FALSE;
            return false;
        }
    }

    // ── Divergence-state pfsf_divergence_state kind codes ───────────────

    public static final class DivergenceKind {
        public static final int NONE            = 0;
        public static final int NAN_INF         = 1;
        public static final int RAPID_GROWTH    = 2;
        public static final int OSCILLATION     = 3;
        public static final int PERSISTENT_OSC  = 4;
        public static final int MACRO_REGION    = 5;
        private DivergenceKind() {}
    }

    // ── v0.3d Phase 5 — extension SPI bridge (augmentation + hooks) ─────

    /**
     * Register a per-voxel augmentation DirectByteBuffer slot for an
     * island. {@code dbb} must be a direct buffer — the native side
     * grabs its address via {@code GetDirectBufferAddress} and caches
     * it verbatim; the buffer must outlive the registration.
     *
     * @see pfsf_extension.h {@code pfsf_aug_register}
     */
    public static native int nativeAugRegister(int islandId, int kind,
                                                 java.nio.ByteBuffer dbb,
                                                 int strideBytes,
                                                 int version);

    /** @see pfsf_extension.h {@code pfsf_aug_clear} */
    public static native void nativeAugClear(int islandId, int kind);

    /** @see pfsf_extension.h {@code pfsf_aug_clear_island} */
    public static native void nativeAugClearIsland(int islandId);

    /** @see pfsf_extension.h {@code pfsf_aug_island_count} */
    public static native int nativeAugIslandCount(int islandId);

    /**
     * @return slot version number when present, -1 when not registered.
     * @see pfsf_extension.h {@code pfsf_aug_query}
     */
    public static native int nativeAugQueryVersion(int islandId, int kind);

    /**
     * Full slot fetch — populates {@code out[4]} with
     * {@code [kind, strideBytes, version, dbbBytesLow32]}.
     *
     * @return true when the slot was present, false otherwise.
     */
    public static native boolean nativeAugQuery(int islandId, int kind, int[] out);

    /** @see pfsf_extension.h {@code pfsf_hook_clear} */
    public static native void nativeHookClear(int islandId, int point);

    /** @see pfsf_extension.h {@code pfsf_hook_clear_island} */
    public static native void nativeHookClearIsland(int islandId);

    // ── v0.3d Phase 5 — compute.v5 feature probe cache ──────────────────

    private static volatile Boolean COMPUTE_V5_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes Phase 5 extension SPI
     *         storage (augmentation registry + hook table).
     */
    public static boolean hasComputeV5() {
        Boolean cached = COMPUTE_V5_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V5_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v5");
            COMPUTE_V5_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v5 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            COMPUTE_V5_CACHE = Boolean.FALSE;
            return false;
        }
    }

    /** Mirrors {@code pfsf_augmentation_kind}. */
    public static final class AugKind {
        public static final int THERMAL_FIELD    = 1;
        public static final int TENSION_OVERRIDE = 2;
        public static final int FLUID_PRESSURE   = 3;
        public static final int EM_FIELD         = 4;
        public static final int FUSION_MASK      = 5;
        public static final int WIND_FIELD_3D    = 6;
        public static final int MATERIAL_OVR     = 7;
        public static final int CURING_FIELD     = 8;
        public static final int LOADPATH_HINT    = 9;
        private AugKind() {}
    }

    /** Mirrors {@code pfsf_hook_point}. */
    public static final class HookPoint {
        public static final int PRE_SOURCE  = 0;
        public static final int POST_SOURCE = 1;
        public static final int PRE_SOLVE   = 2;
        public static final int POST_SOLVE  = 3;
        public static final int PRE_SCAN    = 4;
        public static final int POST_SCAN   = 5;
        private HookPoint() {}
    }

    // ── v0.3d Phase 6 — tick plan dispatcher bridge ─────────────────────

    /**
     * Execute a tick plan — single JNI round-trip for a whole batch of
     * opcodes. The buffer must be a direct buffer in little-endian
     * layout as described in {@code pfsf_plan.h}; {@code planBytes} is
     * the number of bytes actually populated (not capacity).
     *
     * @param outResult int[4]: [executed, failedIndex, errorCode, hookFireCount]
     * @return {@code PFSFResult} code; OK when every opcode executed.
     * @see pfsf_plan.h {@code pfsf_plan_execute}
     */
    public static native int nativePlanExecute(java.nio.ByteBuffer planDbb,
                                                 long planBytes,
                                                 int[] outResult);

    /** @see pfsf_plan.h {@code pfsf_plan_test_counter_read_reset} */
    public static native long nativePlanTestCounterReadReset();

    /** @see pfsf_plan.h {@code pfsf_plan_test_hook_install} */
    public static native void nativePlanTestHookInstall(int islandId, int point);

    /** @see pfsf_plan.h {@code pfsf_plan_test_hook_count_read_reset} */
    public static native long nativePlanTestHookCountReadReset(int islandId, int point);

    // ── v0.3d Phase 6 — compute.v6 feature probe cache ──────────────────

    private static volatile Boolean COMPUTE_V6_CACHE = null;

    /**
     * @return whether libpfsf_compute exposes Phase 6 plan buffer
     *         dispatcher ({@code pfsf_plan_execute} + test helpers).
     */
    public static boolean hasComputeV6() {
        Boolean cached = COMPUTE_V6_CACHE;
        if (cached != null) return cached;
        if (!LIBRARY_LOADED) {
            COMPUTE_V6_CACHE = Boolean.FALSE;
            return false;
        }
        try {
            boolean r = nativeHasFeature("compute.v6");
            COMPUTE_V6_CACHE = r;
            if (r) {
                LOGGER.info("NativePFSFBridge: compute.v6 available ({})",
                        safeBuildInfo());
            }
            return r;
        } catch (UnsatisfiedLinkError e) {
            COMPUTE_V6_CACHE = Boolean.FALSE;
            return false;
        }
    }

    /** Mirrors {@code pfsf_plan_opcode}. */
    public static final class PlanOp {
        public static final int NO_OP             = 0;
        public static final int INCR_COUNTER      = 1;
        public static final int CLEAR_AUG         = 2;
        public static final int CLEAR_AUG_ISLAND  = 3;
        public static final int FIRE_HOOK         = 4;
        private PlanOp() {}
    }

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
