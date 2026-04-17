/**
 * @file native_pfsf_bridge.cpp
 * @brief JNI bridge — exposes libpfsf C API to the Java side.
 *
 * Counterpart: com.blockreality.api.physics.pfsf.NativePFSFBridge
 *
 * Design notes (v0.3c Phase 1):
 *   - Opaque jlong handles carry pfsf_engine across the JNI boundary —
 *     zero JVM object allocation per call.
 *   - Lifecycle + island + tick + stats are exposed. The Java-side callbacks
 *     (material/anchor/fill_ratio/curing lookup) are staged for Phase 1b;
 *     Phase 1 ships the scaffolding and a lookup-less tick so the Java
 *     side can validate end-to-end wiring via isAvailable()/version().
 *   - All methods catch native exceptions so JNI never escapes with a
 *     C++ exception (which is UB across the ABI boundary).
 */

#include <jni.h>
#include <pfsf/pfsf.h>

#include <cstring>
#include <cstdlib>
#include <new>

#if defined(PFSF_USE_BR_CORE)
#include "br_core/jni_helpers.h"
#endif

/* ─── JNI_OnLoad — capture the JavaVM so background C++ threads can
 *     attach for Java callbacks (anchor invalidate, failure batches,
 *     island evictions). Forwarded to libbr_core when linked. */
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
#if defined(PFSF_USE_BR_CORE)
    br_core::set_java_vm(vm);
#else
    (void) vm;
#endif
    return JNI_VERSION_1_8;
}

namespace {

inline pfsf_engine as_engine(jlong h) {
    return reinterpret_cast<pfsf_engine>(static_cast<uintptr_t>(h));
}

inline jlong as_handle(pfsf_engine e) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(e));
}

} // namespace

extern "C" {

/* ═══════════════════════════════════════════════════════════════
 *  Lifecycle
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jlong JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeCreate(
        JNIEnv* env, jclass,
        jint    maxIslandSize,
        jint    tickBudgetMs,
        jlong   vramBudgetBytes,
        jboolean enablePhaseField,
        jboolean enableMultigrid) {
    (void) env;

    pfsf_config cfg{};
    cfg.max_island_size    = (maxIslandSize > 0) ? maxIslandSize : 50000;
    cfg.tick_budget_ms     = (tickBudgetMs > 0) ? tickBudgetMs : 8;
    cfg.vram_budget_bytes  = (vramBudgetBytes > 0) ? vramBudgetBytes
                                                    : (512LL * 1024 * 1024);
    cfg.enable_phase_field = (enablePhaseField == JNI_TRUE);
    cfg.enable_multigrid   = (enableMultigrid  == JNI_TRUE);

    pfsf_engine e = pfsf_create(&cfg);
    return as_handle(e);
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeInit(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return PFSF_ERROR_INVALID_ARG;
    return static_cast<jint>(pfsf_init(as_engine(handle)));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeShutdown(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    pfsf_shutdown(as_engine(handle));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    pfsf_destroy(as_engine(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeIsAvailable(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return pfsf_is_available(as_engine(handle)) ? JNI_TRUE : JNI_FALSE;
}

/* ═══════════════════════════════════════════════════════════════
 *  Stats (thread-safe per C API contract)
 *
 *  Returns a 5-element long[] encoding:
 *    [0] island_count           (int32 widened)
 *    [1] total_voxels
 *    [2] vram_used_bytes
 *    [3] vram_budget_bytes
 *    [4] last_tick_ms * 1000    (float→int µs, avoids jfloat[] alloc)
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jlongArray JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeGetStats(
        JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) return nullptr;

    pfsf_stats stats{};
    if (pfsf_get_stats(as_engine(handle), &stats) != PFSF_OK) {
        return nullptr;
    }

    jlongArray out = env->NewLongArray(5);
    if (out == nullptr) return nullptr;

    jlong vals[5] = {
        static_cast<jlong>(stats.island_count),
        static_cast<jlong>(stats.total_voxels),
        static_cast<jlong>(stats.vram_used_bytes),
        static_cast<jlong>(stats.vram_budget_bytes),
        static_cast<jlong>(stats.last_tick_ms * 1000.0f),
    };
    env->SetLongArrayRegion(out, 0, 5, vals);
    return out;
}

/* ═══════════════════════════════════════════════════════════════
 *  Wind
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeSetWind(
        JNIEnv*, jclass, jlong handle,
        jfloat wx, jfloat wy, jfloat wz) {
    if (handle == 0) return;
    pfsf_vec3 w{ wx, wy, wz };
    pfsf_set_wind(as_engine(handle), &w);
}

/* ═══════════════════════════════════════════════════════════════
 *  Island management
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAddIsland(
        JNIEnv*, jclass, jlong handle,
        jint islandId,
        jint ox, jint oy, jint oz,
        jint lx, jint ly, jint lz) {
    if (handle == 0) return PFSF_ERROR_INVALID_ARG;

    pfsf_island_desc desc{};
    desc.island_id = islandId;
    desc.origin.x  = ox;
    desc.origin.y  = oy;
    desc.origin.z  = oz;
    desc.lx = lx;
    desc.ly = ly;
    desc.lz = lz;
    return static_cast<jint>(pfsf_add_island(as_engine(handle), &desc));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeRemoveIsland(
        JNIEnv*, jclass, jlong handle, jint islandId) {
    if (handle == 0) return;
    pfsf_remove_island(as_engine(handle), islandId);
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeMarkFullRebuild(
        JNIEnv*, jclass, jlong handle, jint islandId) {
    if (handle == 0) return;
    pfsf_mark_full_rebuild(as_engine(handle), islandId);
}

/* ═══════════════════════════════════════════════════════════════
 *  Sparse voxel update
 *
 *  The 6-way conductivity array is passed as a packed float[6] region
 *  to avoid building a pfsf_voxel_update via many JNI calls.
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeNotifyBlockChange(
        JNIEnv* env, jclass,
        jlong handle,
        jint islandId,
        jint flatIndex,
        jfloat source,
        jint voxelType,
        jfloat maxPhi,
        jfloat rcomp,
        jfloatArray cond6) {
    if (handle == 0) return PFSF_ERROR_INVALID_ARG;
    if (cond6 == nullptr || env->GetArrayLength(cond6) < 6) return PFSF_ERROR_INVALID_ARG;

    pfsf_voxel_update u{};
    u.flat_index = flatIndex;
    u.source     = source;
    u.type       = static_cast<pfsf_voxel_type>(voxelType);
    u.max_phi    = maxPhi;
    u.rcomp      = rcomp;

    jfloat* src = env->GetFloatArrayElements(cond6, nullptr);
    if (src == nullptr) return PFSF_ERROR_INVALID_ARG;
    for (int i = 0; i < 6; ++i) u.cond[i] = src[i];
    env->ReleaseFloatArrayElements(cond6, src, JNI_ABORT);

    return static_cast<jint>(pfsf_notify_block_change(as_engine(handle), islandId, &u));
}

/* ═══════════════════════════════════════════════════════════════
 *  Tick
 *
 *  dirtyIslandIds may be null (no dirty islands — still advance epoch).
 *  outFailures is a caller-sized int[] encoding failure events as
 *  tuples of (x, y, z, type) — capacity must be a multiple of 4.
 *  Returns the result code; the number of failures written is encoded
 *  in the high 16 bits of the returned jint shifted left by 16 when
 *  PFSF_OK (result codes are small negatives so this is unambiguous).
 *  For a cleaner API the Java side reads the count via the array's
 *  [0] element after the call — see NativePFSFBridge.tick() docs.
 *
 *  Convention: outFailures[0] = count written; outFailures[1..] = events.
 *  Each event is 4 ints: x, y, z, type.
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeTick(
        JNIEnv* env, jclass,
        jlong handle,
        jintArray dirtyIslandIds,
        jlong currentEpoch,
        jintArray outFailures) {
    if (handle == 0) return PFSF_ERROR_INVALID_ARG;

    jint*  dirtyBuf = nullptr;
    jsize  dirtyLen = 0;
    if (dirtyIslandIds != nullptr) {
        dirtyLen = env->GetArrayLength(dirtyIslandIds);
        if (dirtyLen > 0) {
            dirtyBuf = env->GetIntArrayElements(dirtyIslandIds, nullptr);
            if (dirtyBuf == nullptr) return PFSF_ERROR_INVALID_ARG;
        }
    }

    /* Build the failure-event scratch area. */
    pfsf_tick_result tickResult{};
    tickResult.events   = nullptr;
    tickResult.capacity = 0;
    tickResult.count    = 0;

    pfsf_failure_event* eventBuf = nullptr;
    jint outCapacity = 0;
    if (outFailures != nullptr) {
        outCapacity = env->GetArrayLength(outFailures);
        /* Reserve index 0 for the count. Each event costs 4 ints. */
        jint usable = (outCapacity > 1) ? (outCapacity - 1) / 4 : 0;
        if (usable > 0) {
            eventBuf = static_cast<pfsf_failure_event*>(
                std::calloc(static_cast<size_t>(usable), sizeof(pfsf_failure_event)));
            if (eventBuf != nullptr) {
                tickResult.events   = eventBuf;
                tickResult.capacity = usable;
            }
        }
    }

    pfsf_result r = pfsf_tick(
        as_engine(handle),
        reinterpret_cast<const int32_t*>(dirtyBuf),
        static_cast<int32_t>(dirtyLen),
        static_cast<int64_t>(currentEpoch),
        (tickResult.events != nullptr) ? &tickResult : nullptr);

    if (dirtyBuf != nullptr) {
        env->ReleaseIntArrayElements(dirtyIslandIds, dirtyBuf, JNI_ABORT);
    }

    /* Write the failure count + events back to outFailures. */
    if (outFailures != nullptr && outCapacity > 0) {
        jint count = tickResult.count;
        env->SetIntArrayRegion(outFailures, 0, 1, &count);
        if (eventBuf != nullptr && count > 0) {
            /* Serialize as [x,y,z,type] tuples into outFailures[1..]. */
            jint* packed = static_cast<jint*>(std::malloc(sizeof(jint) * 4 * count));
            if (packed != nullptr) {
                for (jint i = 0; i < count; ++i) {
                    packed[i * 4 + 0] = eventBuf[i].pos.x;
                    packed[i * 4 + 1] = eventBuf[i].pos.y;
                    packed[i * 4 + 2] = eventBuf[i].pos.z;
                    packed[i * 4 + 3] = static_cast<jint>(eventBuf[i].type);
                }
                env->SetIntArrayRegion(outFailures, 1, 4 * count, packed);
                std::free(packed);
            }
        }
    }

    if (eventBuf != nullptr) std::free(eventBuf);
    return static_cast<jint>(r);
}

/* ═══════════════════════════════════════════════════════════════
 *  Stress field readback
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeReadStress(
        JNIEnv* env, jclass,
        jlong handle,
        jint islandId,
        jfloatArray outStress) {
    if (handle == 0 || outStress == nullptr) return PFSF_ERROR_INVALID_ARG;

    jsize capacity = env->GetArrayLength(outStress);
    if (capacity <= 0) return PFSF_ERROR_INVALID_ARG;

    jfloat* buf = env->GetFloatArrayElements(outStress, nullptr);
    if (buf == nullptr) return PFSF_ERROR_INVALID_ARG;

    int32_t written = 0;
    pfsf_result r = pfsf_read_stress(
        as_engine(handle), islandId,
        buf, static_cast<int32_t>(capacity), &written);

    /* COMMIT writes the buffer back to Java regardless of partial success. */
    env->ReleaseFloatArrayElements(outStress, buf, 0);
    return (r == PFSF_OK) ? written : static_cast<jint>(r);
}

/* ═══════════════════════════════════════════════════════════════
 *  v0.3c — DirectByteBuffer zero-copy registration
 * ═══════════════════════════════════════════════════════════════ */

namespace {

// Resolve a direct ByteBuffer to {addr, bytes}. Returns {nullptr, 0}
// for null/non-direct inputs — caller treats that as INVALID_ARG.
struct Dbb { void* addr; int64_t bytes; };

inline Dbb resolve_dbb(JNIEnv* env, jobject buf) {
    if (buf == nullptr) return Dbb{ nullptr, 0 };
    void*  a = env->GetDirectBufferAddress(buf);
    jlong  n = env->GetDirectBufferCapacity(buf);
    if (a == nullptr || n < 0) return Dbb{ nullptr, 0 };
    return Dbb{ a, static_cast<int64_t>(n) };
}

} // namespace

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeRegisterIslandBuffers(
        JNIEnv* env, jclass,
        jlong handle, jint islandId,
        jobject phi, jobject source, jobject conductivity,
        jobject voxelType, jobject rcomp, jobject rtens) {
    if (handle == 0) return PFSF_ERROR_NOT_INIT;

    pfsf_island_buffers b{};
    Dbb d;
    d = resolve_dbb(env, phi);          b.phi_addr          = d.addr; b.phi_bytes          = d.bytes;
    d = resolve_dbb(env, source);       b.source_addr       = d.addr; b.source_bytes       = d.bytes;
    d = resolve_dbb(env, conductivity); b.conductivity_addr = d.addr; b.conductivity_bytes = d.bytes;
    d = resolve_dbb(env, voxelType);    b.voxel_type_addr   = d.addr; b.voxel_type_bytes   = d.bytes;
    d = resolve_dbb(env, rcomp);        b.rcomp_addr        = d.addr; b.rcomp_bytes        = d.bytes;
    d = resolve_dbb(env, rtens);        b.rtens_addr        = d.addr; b.rtens_bytes        = d.bytes;

    if (b.phi_addr == nullptr || b.source_addr == nullptr ||
        b.conductivity_addr == nullptr || b.voxel_type_addr == nullptr ||
        b.rcomp_addr == nullptr || b.rtens_addr == nullptr) {
        return PFSF_ERROR_INVALID_ARG;
    }
    return static_cast<jint>(pfsf_register_island_buffers(as_engine(handle), islandId, &b));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeRegisterIslandLookups(
        JNIEnv* env, jclass,
        jlong handle, jint islandId,
        jobject materialId, jobject anchorBitmap,
        jobject fluidPressure, jobject curing) {
    if (handle == 0) return PFSF_ERROR_NOT_INIT;

    pfsf_island_lookups l{};
    Dbb d;
    d = resolve_dbb(env, materialId);    l.material_id_addr    = d.addr; l.material_id_bytes    = d.bytes;
    d = resolve_dbb(env, anchorBitmap);  l.anchor_bitmap_addr  = d.addr; l.anchor_bitmap_bytes  = d.bytes;
    d = resolve_dbb(env, fluidPressure); l.fluid_pressure_addr = d.addr; l.fluid_pressure_bytes = d.bytes;
    d = resolve_dbb(env, curing);        l.curing_addr         = d.addr; l.curing_bytes         = d.bytes;

    if (l.material_id_addr == nullptr || l.anchor_bitmap_addr == nullptr ||
        l.fluid_pressure_addr == nullptr || l.curing_addr == nullptr) {
        return PFSF_ERROR_INVALID_ARG;
    }
    return static_cast<jint>(pfsf_register_island_lookups(as_engine(handle), islandId, &l));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeRegisterStressReadback(
        JNIEnv* env, jclass,
        jlong handle, jint islandId, jobject stress) {
    if (handle == 0) return PFSF_ERROR_NOT_INIT;
    Dbb d = resolve_dbb(env, stress);
    if (d.addr == nullptr) return PFSF_ERROR_INVALID_ARG;
    return static_cast<jint>(
        pfsf_register_stress_readback(as_engine(handle), islandId, d.addr, d.bytes));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeTickDbb(
        JNIEnv* env, jclass,
        jlong handle, jintArray dirtyIslandIds, jlong currentEpoch, jobject failureBuffer) {
    if (handle == 0) return PFSF_ERROR_NOT_INIT;

    jsize dirty_count = (dirtyIslandIds != nullptr) ? env->GetArrayLength(dirtyIslandIds) : 0;
    jint* dirty_ptr   = (dirty_count > 0) ? env->GetIntArrayElements(dirtyIslandIds, nullptr) : nullptr;

    Dbb fb = resolve_dbb(env, failureBuffer);

    pfsf_result r = pfsf_tick_dbb(
        as_engine(handle),
        reinterpret_cast<const int32_t*>(dirty_ptr),
        static_cast<int32_t>(dirty_count),
        static_cast<int64_t>(currentEpoch),
        fb.addr, fb.bytes);

    if (dirty_ptr != nullptr) env->ReleaseIntArrayElements(dirtyIslandIds, dirty_ptr, JNI_ABORT);
    return static_cast<jint>(r);
}

/* ═══════════════════════════════════════════════════════════════
 *  v0.3c M2n — Sparse voxel re-upload (tick-time scatter)
 *
 *  Java obtains a DirectByteBuffer aliased to the VMA-mapped
 *  sparse_upload_mapped pointer, writes up to 512 packed 48-byte
 *  VoxelUpdate records into it, then calls nativeNotifySparseUpdates
 *  to dispatch sparse_scatter.comp.
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jobject JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeGetSparseUploadBuffer(
        JNIEnv* env, jclass,
        jlong handle, jint islandId) {
    if (handle == 0) return nullptr;

    void*   addr  = nullptr;
    int64_t bytes = 0;
    if (pfsf_get_sparse_upload_buffer(as_engine(handle), islandId,
                                      &addr, &bytes) != PFSF_OK) {
        return nullptr;
    }
    if (!addr || bytes <= 0) return nullptr;

    // NewDirectByteBuffer wraps the VMA-owned memory; Java MUST NOT free it.
    // The buffer dies when the island is removed and VMA releases the SSBO.
    return env->NewDirectByteBuffer(addr, static_cast<jlong>(bytes));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeNotifySparseUpdates(
        JNIEnv*, jclass,
        jlong handle, jint islandId, jint updateCount) {
    if (handle == 0) return PFSF_ERROR_NOT_INIT;
    return static_cast<jint>(
        pfsf_notify_sparse_updates(as_engine(handle), islandId,
                                    static_cast<int32_t>(updateCount)));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeDrainCallbacks(
        JNIEnv* env, jclass,
        jlong handle, jintArray outEvents) {
    if (handle == 0 || outEvents == nullptr) return 0;
    jsize cap = env->GetArrayLength(outEvents);
    if (cap <= 0) return 0;

    jint* buf = env->GetIntArrayElements(outEvents, nullptr);
    if (buf == nullptr) return 0;

    int32_t n = pfsf_drain_callbacks(
        as_engine(handle),
        reinterpret_cast<int32_t*>(buf),
        static_cast<int32_t>(cap));

    env->ReleaseIntArrayElements(outEvents, buf, 0);
    return static_cast<jint>(n);
}

/* ═══════════════════════════════════════════════════════════════
 *  Version
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jstring JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeVersion(
        JNIEnv* env, jclass) {
    const char* v = pfsf_version();
    return (v != nullptr) ? env->NewStringUTF(v) : env->NewStringUTF("unknown");
}

/* ═══════════════════════════════════════════════════════════════
 *  v0.3d Phase 1 — ABI / feature probes
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAbiVersion(
        JNIEnv* /*env*/, jclass) {
    return static_cast<jint>(pfsf_abi_version());
}

JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeHasFeature(
        JNIEnv* env, jclass, jstring name) {
    if (name == nullptr) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(name, nullptr);
    if (c == nullptr) return JNI_FALSE;
    const bool r = pfsf_has_feature(c);
    env->ReleaseStringUTFChars(name, c);
    return r ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeBuildInfo(
        JNIEnv* env, jclass) {
    const char* v = pfsf_build_info();
    return (v != nullptr) ? env->NewStringUTF(v) : env->NewStringUTF("n/a");
}

/* ═══════════════════════════════════════════════════════════════
 *  v0.3d Phase 1 — Stateless compute primitives
 *
 *  Single-value primitives pass scalars directly. Array primitives use
 *  Get/ReleasePrimitiveArrayCritical for zero-copy per the v0.3c DBB
 *  guideline on small-to-medium Java arrays — the sparse upload DBB
 *  path is reserved for per-tick voxel traffic.
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeWindPressureSource(
        JNIEnv* /*env*/, jclass,
        jfloat windSpeed, jfloat density, jboolean exposed) {
    return pfsf_wind_pressure_source(
            static_cast<float>(windSpeed),
            static_cast<float>(density),
            exposed == JNI_TRUE);
}

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeTimoshenkoMomentFactor(
        JNIEnv* /*env*/, jclass,
        jfloat sectionWidth, jfloat sectionHeight,
        jint arm, jfloat youngsGPa, jfloat nu) {
    return pfsf_timoshenko_moment_factor(
            static_cast<float>(sectionWidth),
            static_cast<float>(sectionHeight),
            static_cast<int32_t>(arm),
            static_cast<float>(youngsGPa),
            static_cast<float>(nu));
}

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeNormalizeSoA6(
        JNIEnv* env, jclass,
        jfloatArray source, jfloatArray rcomp, jfloatArray rtens,
        jfloatArray conductivity, jfloatArray hydration, jint n) {
    if (source == nullptr || rcomp == nullptr || rtens == nullptr
            || conductivity == nullptr || n <= 0) {
        return 1.0f;
    }

    float* s = static_cast<float*>(env->GetPrimitiveArrayCritical(source,       nullptr));
    float* c = static_cast<float*>(env->GetPrimitiveArrayCritical(conductivity, nullptr));
    float* rc = static_cast<float*>(env->GetPrimitiveArrayCritical(rcomp,       nullptr));
    float* rt = static_cast<float*>(env->GetPrimitiveArrayCritical(rtens,       nullptr));
    float* h  = (hydration != nullptr)
                    ? static_cast<float*>(env->GetPrimitiveArrayCritical(hydration, nullptr))
                    : nullptr;

    float sigma_max = 1.0f;
    if (s && c && rc && rt) {
        pfsf_normalize_soa6(s, rc, rt, c, h, static_cast<int32_t>(n), &sigma_max);
    }

    /* Release in reverse acquisition order. */
    if (h)  env->ReleasePrimitiveArrayCritical(hydration,    h,  0);
    if (rt) env->ReleasePrimitiveArrayCritical(rtens,        rt, 0);
    if (rc) env->ReleasePrimitiveArrayCritical(rcomp,        rc, 0);
    if (c)  env->ReleasePrimitiveArrayCritical(conductivity, c,  0);
    if (s)  env->ReleasePrimitiveArrayCritical(source,       s,  0);

    return sigma_max;
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeApplyWindBias(
        JNIEnv* env, jclass,
        jfloatArray conductivity, jint n,
        jfloat wx, jfloat wy, jfloat wz, jfloat upwindFactor) {
    if (conductivity == nullptr || n <= 0) return;
    float* c = static_cast<float*>(env->GetPrimitiveArrayCritical(conductivity, nullptr));
    if (c == nullptr) return;
    pfsf_vec3 wind{ wx, wy, wz };
    pfsf_apply_wind_bias(c, static_cast<int32_t>(n), wind, static_cast<float>(upwindFactor));
    env->ReleasePrimitiveArrayCritical(conductivity, c, 0);
}

/* ═══════════════════════════════════════════════════════════════════
 *  v0.3d Phase 2 — arm/arch/phantom edges
 *
 *  Inputs are flat grids (byte members[N], byte anchors[N]); outputs are
 *  flat arrays (int32_t arm[N], float arch[N]) or in-place SoA-6
 *  conductivity. All critical regions stay tiny — the heavy BFS + UF
 *  work happens entirely inside the C kernel.
 * ═══════════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeComputeArmMap(
        JNIEnv* env, jclass,
        jbyteArray members, jbyteArray anchors,
        jint lx, jint ly, jint lz,
        jintArray outArm) {
    if (members == nullptr || anchors == nullptr || outArm == nullptr)
        return PFSF_ERROR_INVALID_ARG;
    if (lx <= 0 || ly <= 0 || lz <= 0) return PFSF_ERROR_INVALID_ARG;

    uint8_t* m = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(members, nullptr));
    uint8_t* a = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(anchors, nullptr));
    int32_t* o = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(outArm,  nullptr));

    pfsf_result rc = PFSF_ERROR_INVALID_ARG;
    if (m && a && o) {
        rc = pfsf_compute_arm_map(m, a,
                                   static_cast<int32_t>(lx),
                                   static_cast<int32_t>(ly),
                                   static_cast<int32_t>(lz),
                                   o);
    }

    if (o) env->ReleasePrimitiveArrayCritical(outArm,  o, 0);
    if (a) env->ReleasePrimitiveArrayCritical(anchors, a, JNI_ABORT);
    if (m) env->ReleasePrimitiveArrayCritical(members, m, JNI_ABORT);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeComputeArchFactorMap(
        JNIEnv* env, jclass,
        jbyteArray members, jbyteArray anchors,
        jint lx, jint ly, jint lz,
        jfloatArray outArch) {
    if (members == nullptr || anchors == nullptr || outArch == nullptr)
        return PFSF_ERROR_INVALID_ARG;
    if (lx <= 0 || ly <= 0 || lz <= 0) return PFSF_ERROR_INVALID_ARG;

    uint8_t* m = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(members, nullptr));
    uint8_t* a = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(anchors, nullptr));
    float*   o = static_cast<float*>  (env->GetPrimitiveArrayCritical(outArch, nullptr));

    pfsf_result rc = PFSF_ERROR_INVALID_ARG;
    if (m && a && o) {
        rc = pfsf_compute_arch_factor_map(m, a,
                                           static_cast<int32_t>(lx),
                                           static_cast<int32_t>(ly),
                                           static_cast<int32_t>(lz),
                                           o);
    }

    if (o) env->ReleasePrimitiveArrayCritical(outArch, o, 0);
    if (a) env->ReleasePrimitiveArrayCritical(anchors, a, JNI_ABORT);
    if (m) env->ReleasePrimitiveArrayCritical(members, m, JNI_ABORT);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeInjectPhantomEdges(
        JNIEnv* env, jclass,
        jbyteArray members, jfloatArray conductivity, jfloatArray rcomp,
        jint lx, jint ly, jint lz,
        jfloat edgePenalty, jfloat cornerPenalty) {
    if (members == nullptr || conductivity == nullptr || rcomp == nullptr) return 0;
    if (lx <= 0 || ly <= 0 || lz <= 0) return 0;

    uint8_t* m  = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(members,      nullptr));
    float*   c  = static_cast<float*>  (env->GetPrimitiveArrayCritical(conductivity, nullptr));
    float*   rc = static_cast<float*>  (env->GetPrimitiveArrayCritical(rcomp,        nullptr));

    int32_t injected = 0;
    if (m && c && rc) {
        injected = pfsf_inject_phantom_edges(m, c, rc,
                                              static_cast<int32_t>(lx),
                                              static_cast<int32_t>(ly),
                                              static_cast<int32_t>(lz),
                                              static_cast<float>(edgePenalty),
                                              static_cast<float>(cornerPenalty));
    }

    if (rc) env->ReleasePrimitiveArrayCritical(rcomp,        rc, JNI_ABORT);
    if (c)  env->ReleasePrimitiveArrayCritical(conductivity, c,  0);
    if (m)  env->ReleasePrimitiveArrayCritical(members,      m,  JNI_ABORT);
    return static_cast<jint>(injected);
}

/* ═══════════════════════════════════════════════════════════════════
 *  v0.3d Phase 3 — Morton encode/decode + downsample + tiled_layout
 * ═══════════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeMortonEncode(
        JNIEnv* /*env*/, jclass, jint x, jint y, jint z) {
    return static_cast<jint>(pfsf_morton_encode(
            static_cast<uint32_t>(x),
            static_cast<uint32_t>(y),
            static_cast<uint32_t>(z)));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeMortonDecode(
        JNIEnv* env, jclass, jint code, jintArray outXYZ) {
    if (outXYZ == nullptr) return;
    uint32_t x = 0, y = 0, z = 0;
    pfsf_morton_decode(static_cast<uint32_t>(code), &x, &y, &z);
    jint buf[3] = {
        static_cast<jint>(x),
        static_cast<jint>(y),
        static_cast<jint>(z),
    };
    env->SetIntArrayRegion(outXYZ, 0, 3, buf);
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeDownsample2to1(
        JNIEnv* env, jclass,
        jfloatArray fine, jbyteArray fineType,
        jint lxf, jint lyf, jint lzf,
        jfloatArray coarse, jbyteArray coarseType) {
    if (fine == nullptr || coarse == nullptr) return PFSF_ERROR_INVALID_ARG;
    if (lxf <= 0 || lyf <= 0 || lzf <= 0) return PFSF_ERROR_INVALID_ARG;

    float*   f  = static_cast<float*>  (env->GetPrimitiveArrayCritical(fine,       nullptr));
    uint8_t* ft = fineType != nullptr
                    ? static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(fineType, nullptr))
                    : nullptr;
    float*   c  = static_cast<float*>  (env->GetPrimitiveArrayCritical(coarse,     nullptr));
    uint8_t* ct = coarseType != nullptr
                    ? static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(coarseType, nullptr))
                    : nullptr;

    if (f && c) {
        pfsf_downsample_2to1(f, ft,
                             static_cast<int32_t>(lxf),
                             static_cast<int32_t>(lyf),
                             static_cast<int32_t>(lzf),
                             c, ct);
    }

    if (ct) env->ReleasePrimitiveArrayCritical(coarseType, ct, 0);
    if (c)  env->ReleasePrimitiveArrayCritical(coarse,     c,  0);
    if (ft) env->ReleasePrimitiveArrayCritical(fineType,   ft, JNI_ABORT);
    if (f)  env->ReleasePrimitiveArrayCritical(fine,       f,  JNI_ABORT);
    return PFSF_OK;
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeTiledLayoutBuild(
        JNIEnv* env, jclass,
        jfloatArray linear, jint lx, jint ly, jint lz,
        jint tile, jfloatArray out) {
    if (linear == nullptr || out == nullptr) return PFSF_ERROR_INVALID_ARG;
    if (lx <= 0 || ly <= 0 || lz <= 0 || tile <= 0) return PFSF_ERROR_INVALID_ARG;

    float* src = static_cast<float*>(env->GetPrimitiveArrayCritical(linear, nullptr));
    float* dst = static_cast<float*>(env->GetPrimitiveArrayCritical(out,    nullptr));

    if (src && dst) {
        pfsf_tiled_layout_build(src,
                                static_cast<int32_t>(lx),
                                static_cast<int32_t>(ly),
                                static_cast<int32_t>(lz),
                                static_cast<int32_t>(tile),
                                dst);
    }

    if (dst) env->ReleasePrimitiveArrayCritical(out,    dst, 0);
    if (src) env->ReleasePrimitiveArrayCritical(linear, src, JNI_ABORT);
    return PFSF_OK;
}

/* ═══════════════════════════════════════════════════════════════════
 *  v0.3d Phase 4 — diagnostics primitives (chebyshev / spectral /
 *  recommend_steps / macro_block / divergence / features)
 * ═══════════════════════════════════════════════════════════════════ */

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeChebyshevOmega(
        JNIEnv* /*env*/, jclass, jint iter, jfloat rhoSpec) {
    return static_cast<jfloat>(pfsf_chebyshev_omega(
            static_cast<int32_t>(iter),
            static_cast<float>(rhoSpec)));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativePrecomputeOmegaTable(
        JNIEnv* env, jclass, jfloat rhoSpec, jfloatArray out) {
    if (out == nullptr) return PFSF_ERROR_INVALID_ARG;
    const jsize cap = env->GetArrayLength(out);
    if (cap <= 0) return PFSF_ERROR_INVALID_ARG;

    float* p = static_cast<float*>(env->GetPrimitiveArrayCritical(out, nullptr));
    int32_t n = 0;
    if (p) {
        n = pfsf_precompute_omega_table(
                static_cast<float>(rhoSpec),
                p,
                static_cast<int32_t>(cap));
        env->ReleasePrimitiveArrayCritical(out, p, 0);
    }
    return static_cast<jint>(n);
}

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeEstimateSpectralRadius(
        JNIEnv* /*env*/, jclass, jint lMax, jfloat safetyMargin) {
    return static_cast<jfloat>(pfsf_estimate_spectral_radius(
            static_cast<int32_t>(lMax),
            static_cast<float>(safetyMargin)));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeRecommendSteps(
        JNIEnv* /*env*/, jclass,
        jint ly, jint chebyIter, jboolean isDirty, jboolean hasCollapse,
        jint stepsMinor, jint stepsMajor, jint stepsCollapse) {
    return static_cast<jint>(pfsf_recommend_steps(
            static_cast<int32_t>(ly),
            static_cast<int32_t>(chebyIter),
            isDirty    == JNI_TRUE ? 1 : 0,
            hasCollapse == JNI_TRUE ? 1 : 0,
            static_cast<int32_t>(stepsMinor),
            static_cast<int32_t>(stepsMajor),
            static_cast<int32_t>(stepsCollapse)));
}

JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeMacroBlockActive(
        JNIEnv* /*env*/, jclass, jfloat residual, jboolean wasActive) {
    return pfsf_macro_block_active(
            static_cast<float>(residual),
            wasActive == JNI_TRUE ? 1 : 0)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeMacroActiveRatio(
        JNIEnv* env, jclass,
        jfloatArray residuals, jbyteArray wasActive) {
    if (residuals == nullptr) return 1.0f;
    const jsize n = env->GetArrayLength(residuals);
    if (n <= 0) return 1.0f;

    float*   r = static_cast<float*>(env->GetPrimitiveArrayCritical(residuals, nullptr));
    uint8_t* w = wasActive != nullptr
                 ? static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(wasActive, nullptr))
                 : nullptr;

    float ratio = 1.0f;
    if (r) {
        ratio = pfsf_macro_active_ratio(r, static_cast<int32_t>(n), w);
    }
    if (w) env->ReleasePrimitiveArrayCritical(wasActive, w, JNI_ABORT);
    if (r) env->ReleasePrimitiveArrayCritical(residuals, r, JNI_ABORT);
    return static_cast<jfloat>(ratio);
}

/**
 * Divergence check — @p stateInOut is a 7-element int-encoded view of
 * pfsf_divergence_state (so Java can round-trip it without pinning a
 * DirectByteBuffer). Layout (all int32_t, floats bit-cast):
 *   [0] struct_bytes   [1] prev_max_phi (float bits)
 *   [2] prev_prev_max_phi (float bits)   [3] oscillation_count
 *   [4] damping_active   [5] chebyshev_iter
 *   [6] prev_max_macro_residual (float bits)
 */
JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeCheckDivergence(
        JNIEnv* env, jclass,
        jintArray stateInOut,
        jfloat maxPhiNow,
        jfloatArray macroResiduals,
        jfloat divergenceRatio,
        jfloat dampingSettleThreshold) {
    if (stateInOut == nullptr) return PFSF_ERROR_INVALID_ARG;
    if (env->GetArrayLength(stateInOut) < 7) return PFSF_ERROR_INVALID_ARG;

    jint* s = static_cast<jint*>(env->GetPrimitiveArrayCritical(stateInOut, nullptr));
    if (s == nullptr) return PFSF_ERROR_INVALID_ARG;

    pfsf_divergence_state st;
    st.struct_bytes             = static_cast<int32_t>(sizeof(st));
    union { int32_t i; float f; } conv;
    conv.i = s[1]; st.prev_max_phi            = conv.f;
    conv.i = s[2]; st.prev_prev_max_phi       = conv.f;
    st.oscillation_count        = static_cast<int32_t>(s[3]);
    st.damping_active           = static_cast<int32_t>(s[4]);
    st.chebyshev_iter           = static_cast<int32_t>(s[5]);
    conv.i = s[6]; st.prev_max_macro_residual = conv.f;

    float* r = nullptr;
    int32_t rn = 0;
    if (macroResiduals != nullptr) {
        rn = static_cast<int32_t>(env->GetArrayLength(macroResiduals));
        if (rn > 0) {
            r = static_cast<float*>(env->GetPrimitiveArrayCritical(macroResiduals, nullptr));
        }
    }

    int32_t kind = pfsf_check_divergence(&st, static_cast<float>(maxPhiNow),
                                         r, rn,
                                         static_cast<float>(divergenceRatio),
                                         static_cast<float>(dampingSettleThreshold));

    if (r) env->ReleasePrimitiveArrayCritical(macroResiduals, r, JNI_ABORT);

    /* Write back mutated state. */
    s[0] = static_cast<jint>(st.struct_bytes);
    conv.f = st.prev_max_phi;            s[1] = conv.i;
    conv.f = st.prev_prev_max_phi;       s[2] = conv.i;
    s[3]   = static_cast<jint>(st.oscillation_count);
    s[4]   = static_cast<jint>(st.damping_active);
    s[5]   = static_cast<jint>(st.chebyshev_iter);
    conv.f = st.prev_max_macro_residual; s[6] = conv.i;

    env->ReleasePrimitiveArrayCritical(stateInOut, s, 0);
    return static_cast<jint>(kind);
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeExtractIslandFeatures(
        JNIEnv* env, jclass,
        jint lx, jint ly, jint lz,
        jint chebyshevIter,
        jfloat rhoSpecOverride,
        jfloat prevMaxMacroResidual,
        jint oscillationCount,
        jboolean dampingActive,
        jint stableTickCount,
        jint lodLevel,
        jint lodDormant,
        jboolean pcgAllocated,
        jfloatArray macroResiduals,
        jfloatArray out12) {
    if (out12 == nullptr) return;
    if (env->GetArrayLength(out12) < 12) return;

    float*  o = static_cast<float*>(env->GetPrimitiveArrayCritical(out12, nullptr));
    if (o == nullptr) return;

    float*  r  = nullptr;
    int32_t rn = 0;
    if (macroResiduals != nullptr) {
        rn = static_cast<int32_t>(env->GetArrayLength(macroResiduals));
        if (rn > 0) {
            r = static_cast<float*>(env->GetPrimitiveArrayCritical(macroResiduals, nullptr));
        }
    }

    pfsf_extract_island_features(
            static_cast<int32_t>(lx),
            static_cast<int32_t>(ly),
            static_cast<int32_t>(lz),
            static_cast<int32_t>(chebyshevIter),
            static_cast<float>(rhoSpecOverride),
            static_cast<float>(prevMaxMacroResidual),
            static_cast<int32_t>(oscillationCount),
            dampingActive == JNI_TRUE ? 1 : 0,
            static_cast<int32_t>(stableTickCount),
            static_cast<int32_t>(lodLevel),
            static_cast<int32_t>(lodDormant),
            pcgAllocated  == JNI_TRUE ? 1 : 0,
            r, rn,
            o);

    if (r) env->ReleasePrimitiveArrayCritical(macroResiduals, r, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(out12, o, 0);
}

// ── v0.3d Phase 5 — extension SPI bridge (augmentation + hook table) ──
//
// pfsf_aug_* and pfsf_hook_* live behind the compute.v5 feature probe.
// Slots are addressed by (island_id, kind) and backed by a process-wide
// registry inside libpfsf_compute. dbb_addr is the result of
// GetDirectBufferAddress on the Java side — the library only stores the
// pointer; lifetime stays with Java.

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugRegister(
        JNIEnv* env, jclass,
        jint islandId,
        jint kind,
        jobject dbb,
        jint strideBytes,
        jint version) {
    pfsf_aug_slot slot{};
    slot.struct_bytes = static_cast<int32_t>(sizeof(pfsf_aug_slot));
    slot.kind         = static_cast<pfsf_augmentation_kind>(kind);
    slot.dbb_addr     = (dbb != nullptr) ? env->GetDirectBufferAddress(dbb) : nullptr;
    slot.dbb_bytes    = (dbb != nullptr) ? env->GetDirectBufferCapacity(dbb) : 0;
    slot.stride_bytes = static_cast<int32_t>(strideBytes);
    slot.version      = static_cast<int32_t>(version);

    if (slot.dbb_addr == nullptr) return PFSF_ERROR_INVALID_ARG;
    return pfsf_aug_register(static_cast<int32_t>(islandId), &slot);
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugClear(
        JNIEnv*, jclass, jint islandId, jint kind) {
    pfsf_aug_clear(static_cast<int32_t>(islandId),
                   static_cast<pfsf_augmentation_kind>(kind));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugClearIsland(
        JNIEnv*, jclass, jint islandId) {
    pfsf_aug_clear_island(static_cast<int32_t>(islandId));
}

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugIslandCount(
        JNIEnv*, jclass, jint islandId) {
    return pfsf_aug_island_count(static_cast<int32_t>(islandId));
}

/* Query returns the slot version if present, -1 when missing. Enough for
 * the Java host to detect content drift without pulling the full slot
 * across the boundary. */
JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugQueryVersion(
        JNIEnv*, jclass, jint islandId, jint kind) {
    pfsf_aug_slot out{};
    if (!pfsf_aug_query(static_cast<int32_t>(islandId),
                        static_cast<pfsf_augmentation_kind>(kind),
                        &out)) return -1;
    return static_cast<jint>(out.version);
}

/* Full slot query — populates out[4]: [kind, strideBytes, version, bytesLow32].
 * dbb_bytes is int64 but int32 is enough for our worst case; we stash the
 * low 32 bits for parity tests. */
JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeAugQuery(
        JNIEnv* env, jclass, jint islandId, jint kind, jintArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 4) return JNI_FALSE;
    pfsf_aug_slot s{};
    if (!pfsf_aug_query(static_cast<int32_t>(islandId),
                        static_cast<pfsf_augmentation_kind>(kind),
                        &s)) return JNI_FALSE;
    jint v[4] = {
        static_cast<jint>(s.kind),
        static_cast<jint>(s.stride_bytes),
        static_cast<jint>(s.version),
        static_cast<jint>(s.dbb_bytes & 0xFFFFFFFFll),
    };
    env->SetIntArrayRegion(out, 0, 4, v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeHookClear(
        JNIEnv*, jclass, jint islandId, jint point) {
    pfsf_hook_clear(static_cast<int32_t>(islandId),
                    static_cast<pfsf_hook_point>(point));
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativeHookClearIsland(
        JNIEnv*, jclass, jint islandId) {
    pfsf_hook_clear_island(static_cast<int32_t>(islandId));
}

/* nativeHookSet / nativeHookFire intentionally omitted: hook callbacks
 * must originate from C (they are C function pointers), not Java. Phase
 * 6 plumbs plan-buffer opcodes and the C++ side fires hooks internally.
 * Tests drive hooks via a separate C-side stub registered in Phase 6.
 */

// ── v0.3d Phase 6 — tick plan buffer dispatcher ─────────────────────
//
// Java assembles a single DirectByteBuffer per tick containing
// length-prefixed opcode records; libpfsf_compute's plan_dispatcher
// walks them in one JNI call. This is the boundary-cost amortisation
// that replaces 40+ per-primitive JNI crossings in v0.3c.

JNIEXPORT jint JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativePlanExecute(
        JNIEnv* env, jclass,
        jobject planDbb,
        jlong planBytes,
        jintArray outResult) {
    if (planDbb == nullptr) return PFSF_ERROR_INVALID_ARG;

    void* base = env->GetDirectBufferAddress(planDbb);
    if (base == nullptr) return PFSF_ERROR_INVALID_ARG;

    const int64_t capacity = env->GetDirectBufferCapacity(planDbb);
    int64_t bytes = static_cast<int64_t>(planBytes);
    if (bytes < 0 || (capacity >= 0 && bytes > capacity)) {
        return PFSF_ERROR_INVALID_ARG;
    }

    pfsf_plan_result r{};
    const pfsf_result code = pfsf_plan_execute(base, bytes, &r);

    if (outResult != nullptr && env->GetArrayLength(outResult) >= 4) {
        jint v[4] = {
            static_cast<jint>(r.executed_count),
            static_cast<jint>(r.failed_index),
            static_cast<jint>(r.error_code),
            static_cast<jint>(r.hook_fire_count),
        };
        env->SetIntArrayRegion(outResult, 0, 4, v);
    }
    return static_cast<jint>(code);
}

JNIEXPORT jlong JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativePlanTestCounterReadReset(
        JNIEnv*, jclass) {
    return static_cast<jlong>(pfsf_plan_test_counter_read_reset());
}

JNIEXPORT void JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativePlanTestHookInstall(
        JNIEnv*, jclass, jint islandId, jint point) {
    pfsf_plan_test_hook_install(static_cast<int32_t>(islandId),
                                static_cast<int32_t>(point));
}

JNIEXPORT jlong JNICALL
Java_com_blockreality_api_physics_pfsf_NativePFSFBridge_nativePlanTestHookCountReadReset(
        JNIEnv*, jclass, jint islandId, jint point) {
    return static_cast<jlong>(pfsf_plan_test_hook_count_read_reset(
            static_cast<int32_t>(islandId),
            static_cast<int32_t>(point)));
}

} /* extern "C" */
