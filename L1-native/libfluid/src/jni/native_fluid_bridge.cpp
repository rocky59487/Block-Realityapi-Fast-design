/**
 * @file native_fluid_bridge.cpp
 * @brief JNI bridge for libfluid — counterpart to
 *        com.blockreality.api.physics.fluid.NativeFluidBridge.
 */
#include <jni.h>
#include <fluid/fluid.h>

#include "br_core/jni_helpers.h"

namespace {

inline fluid_engine as_engine(jlong h) {
    return reinterpret_cast<fluid_engine>(static_cast<uintptr_t>(h));
}

inline jlong as_handle(fluid_engine e) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(e));
}

struct Dbb { void* addr; int64_t bytes; };
inline Dbb resolve_dbb(JNIEnv* env, jobject buf) {
    if (buf == nullptr) return Dbb{ nullptr, 0 };
    void* a = env->GetDirectBufferAddress(buf);
    jlong n = env->GetDirectBufferCapacity(buf);
    if (a == nullptr || n < 0) return Dbb{ nullptr, 0 };
    return Dbb{ a, static_cast<int64_t>(n) };
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    br_core::set_java_vm(vm);
    return JNI_VERSION_1_8;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeCreate(
        JNIEnv*, jclass,
        jint maxIslandSize, jint tickBudgetMs, jlong vramBudgetBytes,
        jboolean enableSurfaceTension, jboolean enableCoupling) {
    fluid_config cfg{};
    cfg.max_island_size        = maxIslandSize > 0 ? maxIslandSize : 50000;
    cfg.tick_budget_ms         = tickBudgetMs  > 0 ? tickBudgetMs  : 8;
    cfg.vram_budget_bytes      = vramBudgetBytes > 0 ? vramBudgetBytes : (128LL * 1024 * 1024);
    cfg.enable_surface_tension = (enableSurfaceTension == JNI_TRUE);
    cfg.enable_coupling        = (enableCoupling       == JNI_TRUE);
    return as_handle(fluid_create(&cfg));
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeInit(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(fluid_init(as_engine(h)));
}

JNIEXPORT void    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeShutdown(JNIEnv*, jclass, jlong h) {
    fluid_shutdown(as_engine(h));
}

JNIEXPORT void    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeDestroy(JNIEnv*, jclass, jlong h) {
    fluid_destroy(as_engine(h));
}

JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeIsAvailable(JNIEnv*, jclass, jlong h) {
    return fluid_is_available(as_engine(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeAddIsland(
        JNIEnv*, jclass, jlong h, jint id, jint lx, jint ly, jint lz) {
    return static_cast<jint>(fluid_add_island(as_engine(h), id, lx, ly, lz));
}

JNIEXPORT void    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeRemoveIsland(
        JNIEnv*, jclass, jlong h, jint id) {
    fluid_remove_island(as_engine(h), id);
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeRegisterIslandBuffers(
        JNIEnv* env, jclass, jlong h, jint id,
        jobject pressure, jobject velocity, jobject flux, jobject levelSet) {
    fluid_island_buffers b{};
    Dbb d;
    d = resolve_dbb(env, pressure); b.pressure_addr  = d.addr; b.pressure_bytes  = d.bytes;
    d = resolve_dbb(env, velocity); b.velocity_addr  = d.addr; b.velocity_bytes  = d.bytes;
    d = resolve_dbb(env, flux);     b.flux_addr      = d.addr; b.flux_bytes      = d.bytes;
    d = resolve_dbb(env, levelSet); b.level_set_addr = d.addr; b.level_set_bytes = d.bytes;
    return static_cast<jint>(fluid_register_island_buffers(as_engine(h), id, &b));
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeTick(
        JNIEnv* env, jclass, jlong h, jintArray dirty, jlong epoch) {
    jsize n   = (dirty != nullptr) ? env->GetArrayLength(dirty) : 0;
    jint* ptr = (n > 0) ? env->GetIntArrayElements(dirty, nullptr) : nullptr;
    fluid_result r = fluid_tick(as_engine(h),
        reinterpret_cast<const int32_t*>(ptr), static_cast<int32_t>(n),
        static_cast<int64_t>(epoch));
    if (ptr != nullptr) env->ReleaseIntArrayElements(dirty, ptr, JNI_ABORT);
    return static_cast<jint>(r);
}

JNIEXPORT jstring JNICALL
Java_com_blockreality_api_physics_fluid_NativeFluidBridge_nativeVersion(JNIEnv* env, jclass) {
    const char* v = fluid_version();
    return env->NewStringUTF(v ? v : "unknown");
}

} // extern "C"
