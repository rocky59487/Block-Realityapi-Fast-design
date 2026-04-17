/**
 * @file jni_helpers.cpp
 * @brief JavaVM capture + DirectByteBuffer fast path.
 */
#if defined(BR_CORE_HAS_JNI)
#include "br_core/jni_helpers.h"

#include <atomic>
#include <cstdio>

namespace br_core {

namespace {
std::atomic<JavaVM*> g_vm{ nullptr };
}

void set_java_vm(JavaVM* vm) {
    g_vm.store(vm, std::memory_order_release);
}

JavaVM* get_java_vm() {
    return g_vm.load(std::memory_order_acquire);
}

JNIEnv* attach_current_thread() {
    JavaVM* vm = get_java_vm();
    if (vm == nullptr) return nullptr;

    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (r == JNI_OK) return env;
    if (r != JNI_EDETACHED) return nullptr;

    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name    = const_cast<char*>("br_core-native");
    args.group   = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) {
        return nullptr;
    }
    return env;
}

void detach_current_thread() {
    JavaVM* vm = get_java_vm();
    if (vm != nullptr) vm->DetachCurrentThread();
}

DirectBufferView direct_buffer(JNIEnv* env, jobject buf) {
    DirectBufferView v{ nullptr, 0 };
    if (env == nullptr || buf == nullptr) return v;
    v.addr = env->GetDirectBufferAddress(buf);
    v.size = env->GetDirectBufferCapacity(buf);
    if (v.addr == nullptr) v.size = 0;
    return v;
}

} // namespace br_core
#endif // BR_CORE_HAS_JNI
