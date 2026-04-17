/**
 * @file jni_helpers.h
 * @brief RAII helpers for JNI — direct buffer address caching,
 *        critical-array scopes, JavaVM capture for callbacks.
 */
#ifndef BR_CORE_JNI_HELPERS_H
#define BR_CORE_JNI_HELPERS_H

#if defined(BR_CORE_HAS_JNI)
#include <jni.h>
#include <cstdint>

namespace br_core {

/** Capture the JavaVM at library load time so C++ callbacks can attach. */
void set_java_vm(JavaVM* vm);
JavaVM* get_java_vm();

/** Attach the current thread (idempotent). Returns nullptr on failure. */
JNIEnv* attach_current_thread();
void    detach_current_thread();

/**
 * Address + capacity of a DirectByteBuffer. Returns {nullptr, 0} on
 * non-direct or null buffers. Zero-copy path for our DBB-based
 * voxel marshalling (plan §B.2).
 */
struct DirectBufferView {
    void*        addr;
    std::int64_t size;
};
DirectBufferView direct_buffer(JNIEnv* env, jobject buf);

/**
 * Scoped primitive-array pin (critical). Moves-only; releases on
 * destruction with JNI_ABORT (no write-back) unless commit() is called.
 * Use only for short, bounded spans — the JVM's GC is stopped.
 */
template <typename T>
class CriticalArray {
public:
    CriticalArray(JNIEnv* env, jarray arr)
        : env_(env), arr_(arr), ptr_(nullptr), commit_mode_(JNI_ABORT) {
        if (arr_ != nullptr) {
            ptr_ = static_cast<T*>(env_->GetPrimitiveArrayCritical(arr_, nullptr));
        }
    }
    ~CriticalArray() {
        if (ptr_ != nullptr) {
            env_->ReleasePrimitiveArrayCritical(arr_, ptr_, commit_mode_);
        }
    }
    CriticalArray(const CriticalArray&)            = delete;
    CriticalArray& operator=(const CriticalArray&) = delete;

    T* data() const { return ptr_; }
    explicit operator bool() const { return ptr_ != nullptr; }
    void commit() { commit_mode_ = 0; }

private:
    JNIEnv* env_;
    jarray  arr_;
    T*      ptr_;
    jint    commit_mode_;
};

} // namespace br_core

#endif // BR_CORE_HAS_JNI
#endif // BR_CORE_JNI_HELPERS_H
