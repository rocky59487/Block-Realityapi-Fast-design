/**
 * @file native_render_bridge.cpp
 * @brief JNI bridge for libblockreality_render — counterpart to
 *        com.blockreality.api.client.render.rt.NativeRenderBridge.
 */
#include <jni.h>
#include <render/render.h>

#include "br_core/jni_helpers.h"

namespace {

inline render_engine as_engine(jlong h) {
    return reinterpret_cast<render_engine>(static_cast<uintptr_t>(h));
}

inline jlong as_handle(render_engine e) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(e));
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    br_core::set_java_vm(vm);
    return JNI_VERSION_1_8;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeCreate(
        JNIEnv*, jclass,
        jint width, jint height, jlong vramBudget, jint tierOverride,
        jboolean restir, jboolean ddgi, jboolean relax) {
    render_config cfg{};
    cfg.width             = width  > 0 ? width  : 1920;
    cfg.height            = height > 0 ? height : 1080;
    cfg.vram_budget_bytes = vramBudget > 0 ? vramBudget : (1024LL * 1024 * 1024);
    cfg.tier_override     = static_cast<render_tier>(tierOverride);
    cfg.enable_restir     = (restir == JNI_TRUE);
    cfg.enable_ddgi       = (ddgi   == JNI_TRUE);
    cfg.enable_relax      = (relax  == JNI_TRUE);
    return as_handle(render_create(&cfg));
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeInit(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(render_init(as_engine(h)));
}

JNIEXPORT void    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeShutdown(JNIEnv*, jclass, jlong h) {
    render_shutdown(as_engine(h));
}

JNIEXPORT void    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeDestroy(JNIEnv*, jclass, jlong h) {
    render_destroy(as_engine(h));
}

JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeIsAvailable(JNIEnv*, jclass, jlong h) {
    return render_is_available(as_engine(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeActiveTier(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(render_active_tier(as_engine(h)));
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeUpdateCameraDbb(
        JNIEnv* env, jclass, jlong h, jobject cameraUbo) {
    if (cameraUbo == nullptr) return RENDER_ERROR_INVALID_ARG;
    void* a = env->GetDirectBufferAddress(cameraUbo);
    jlong n = env->GetDirectBufferCapacity(cameraUbo);
    if (a == nullptr || n < 0) return RENDER_ERROR_INVALID_ARG;
    return static_cast<jint>(render_update_camera_dbb(as_engine(h), a, static_cast<int64_t>(n)));
}

JNIEXPORT jint    JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeSubmitFrame(
        JNIEnv*, jclass, jlong h, jlong frameIndex) {
    return static_cast<jint>(render_submit_frame(as_engine(h), frameIndex));
}

JNIEXPORT jstring JNICALL
Java_com_blockreality_api_client_render_rt_NativeRenderBridge_nativeVersion(JNIEnv* env, jclass) {
    const char* v = render_version();
    return env->NewStringUTF(v ? v : "unknown");
}

} // extern "C"
