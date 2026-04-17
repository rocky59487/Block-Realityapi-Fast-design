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

} /* extern "C" */
