/**
 * BRNRDNative.cpp — JNI bridge to NVIDIA Real-Time Denoisers (NRD) SDK.
 *
 * Build modes:
 *   - NRD SDK present   : define NRD_AVAILABLE=1 (via CMake find_package)
 *   - NRD SDK absent    : stub mode — all methods return 0/false/no-op
 *
 * The Java side (BRNRDNative.java) handles the UnsatisfiedLinkError gracefully
 * and falls back to BRSVGFDenoiser, so this library only needs to exist; it
 * does NOT need a live GPU or NRD SDK to be loadable.
 *
 * JNI method prefix: Java_com_blockreality_api_client_render_rt_BRNRDNative_
 */

#include <jni.h>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <memory>

// ─── Optional NRD SDK headers ──────────────────────────────────────────────
#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
#  include <NRD.h>        // nrd::CreateDenoiser, nrd::DestroyDenoiser, etc.
#  include <NRDDescs.h>   // nrd::CommonSettings, nrd::DenoiserCreationDesc, etc.
#endif

// ─── Internal denoiser instance ────────────────────────────────────────────

/**
 * Heap-allocated state bundle, cast to jlong for the opaque Java handle.
 * Members are conditionally compiled; in stub mode only metadata is stored.
 */
struct NRDDenoiserInstance {
    int width;
    int height;
    int maxFrames;
    uint32_t frameIndex;

    // View/projection state — updated by setCommonSettings
    float viewMatrix[16];
    float projMatrix[16];
    float cameraPosX, cameraPosY, cameraPosZ;

#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
    nrd::Denoiser* denoiser = nullptr;
#endif

    NRDDenoiserInstance(int w, int h, int f)
        : width(w), height(h), maxFrames(f), frameIndex(0),
          cameraPosX(0.f), cameraPosY(0.f), cameraPosZ(0.f)
    {
        memset(viewMatrix, 0, sizeof(viewMatrix));
        memset(projMatrix, 0, sizeof(projMatrix));
        // Identity defaults
        viewMatrix[0] = viewMatrix[5] = viewMatrix[10] = viewMatrix[15] = 1.f;
        projMatrix[0] = projMatrix[5] = projMatrix[10] = projMatrix[15] = 1.f;
    }

    ~NRDDenoiserInstance() {
#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
        if (denoiser) {
            nrd::DestroyDenoiser(*denoiser);
            denoiser = nullptr;
        }
#endif
    }
};

// Convenience cast helpers
static inline NRDDenoiserInstance* fromHandle(jlong h) {
    return reinterpret_cast<NRDDenoiserInstance*>(static_cast<uintptr_t>(h));
}
static inline jlong toHandle(NRDDenoiserInstance* p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}

// ─── JNI: createDenoiser ───────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_blockreality_api_client_render_rt_BRNRDNative_createDenoiser(
    JNIEnv* /*env*/, jclass /*cls*/,
    jint width, jint height, jint maxFramesToAccumulate)
{
    auto* inst = new (std::nothrow) NRDDenoiserInstance(
        static_cast<int>(width),
        static_cast<int>(height),
        static_cast<int>(maxFramesToAccumulate));

    if (!inst) return 0L;   // allocation failed

#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
    // ── NRD SDK path ──────────────────────────────────────────────────────
    nrd::DenoiserCreationDesc desc{};
    desc.requestedMethods = {
        { nrd::Method::REBLUR_DIFFUSE_SPECULAR, static_cast<uint16_t>(width),
          static_cast<uint16_t>(height) },
        { nrd::Method::RELAX_DIFFUSE_SPECULAR, static_cast<uint16_t>(width),
          static_cast<uint16_t>(height) },
        { nrd::Method::SIGMA_SHADOW, static_cast<uint16_t>(width),
          static_cast<uint16_t>(height) },
    };
    desc.requestedMethodsNum = 3;

    nrd::Denoiser* denoiser = nullptr;
    nrd::Result result = nrd::CreateDenoiser(desc, denoiser);
    if (result != nrd::Result::SUCCESS || !denoiser) {
        delete inst;
        return 0L;
    }
    inst->denoiser = denoiser;
#endif
    // Stub mode: inst is valid but holds no native GPU resource — Java side
    // will call isNrdAvailable() == false and skip all denoise dispatches.

    return toHandle(inst);
}

// ─── JNI: setCommonSettings ────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_blockreality_api_client_render_rt_BRNRDNative_setCommonSettings(
    JNIEnv* env, jclass /*cls*/,
    jlong denoiserHandle,
    jfloatArray viewMatrix, jfloatArray projMatrix,
    jfloat cameraPosX, jfloat cameraPosY, jfloat cameraPosZ)
{
    NRDDenoiserInstance* inst = fromHandle(denoiserHandle);
    if (!inst) return;

    // Copy matrices from JVM heap
    jfloat* vm = env->GetFloatArrayElements(viewMatrix, nullptr);
    jfloat* pm = env->GetFloatArrayElements(projMatrix, nullptr);
    if (vm) { memcpy(inst->viewMatrix, vm, 16 * sizeof(float)); env->ReleaseFloatArrayElements(viewMatrix, vm, JNI_ABORT); }
    if (pm) { memcpy(inst->projMatrix, pm, 16 * sizeof(float)); env->ReleaseFloatArrayElements(projMatrix, pm, JNI_ABORT); }

    inst->cameraPosX = static_cast<float>(cameraPosX);
    inst->cameraPosY = static_cast<float>(cameraPosY);
    inst->cameraPosZ = static_cast<float>(cameraPosZ);

#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
    if (!inst->denoiser) return;

    nrd::CommonSettings common{};
    // worldToView rows: NRD uses column-major; our Java Matrix4f is column-major
    memcpy(common.worldToViewMatrix,     inst->viewMatrix, 64);
    memcpy(common.worldToViewMatrixPrev, inst->viewMatrix, 64); // prev = current on first frame
    memcpy(common.viewToClipMatrix,      inst->projMatrix, 64);
    memcpy(common.viewToClipMatrixPrev,  inst->projMatrix, 64);

    common.cameraJitter[0] = 0.f;
    common.cameraJitter[1] = 0.f;
    common.frameIndex      = inst->frameIndex++;
    common.denoisingRange  = 500.f;   // ~500 m visible range in Minecraft

    nrd::SetCommonSettings(*inst->denoiser, common);
#endif
}

// ─── JNI: denoise ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_blockreality_api_client_render_rt_BRNRDNative_denoise(
    JNIEnv* /*env*/, jclass /*cls*/,
    jlong denoiserHandle,
    jlong inColor, jlong inNormal, jlong inMotion, jlong inDepth,
    jlong outColor)
{
    NRDDenoiserInstance* inst = fromHandle(denoiserHandle);
    if (!inst) return JNI_FALSE;

#if defined(NRD_AVAILABLE) && NRD_AVAILABLE
    if (!inst->denoiser) return JNI_FALSE;

    // ── Texture resource binding ─────────────────────────────────────────
    // The jlong values are Vulkan VkImage handles interoped via BRVulkanInterop.
    // NRD requires NRD-managed resource slots; here we bind the application
    // textures through the dispatch descriptor.
    //
    // NRD v4 Dispatch API (simplified):
    //   nrd::DispatchDesc dispatch{};
    //   dispatch.name       = "REBLUR_DIFFUSE_SPECULAR";
    //   dispatch.resources  = resources;   // array of nrd::Resource
    //   dispatch.resourcesNum = N;
    //   nrd::Dispatch(*inst->denoiser, &dispatch, 1);
    //
    // Full binding requires the host graphics API wrapper (Vulkan/DX12).
    // Block Reality uses BRVulkanDevice as the wrapper — the actual command
    // buffer recording is deferred to BRNRDDenoiser.java via additional JNI
    // calls not yet in this phase (RT-5-2).  For RT-5-1 we validate that
    // the library loads and the handle round-trip works.
    (void)inColor; (void)inNormal; (void)inMotion; (void)inDepth; (void)outColor;

    return JNI_TRUE;   // SDK present; full dispatch wired in RT-5-2
#else
    // Stub mode — caller (BRNRDNative.isNrdAvailable()) returns false,
    // so this code path is unreachable in practice.
    (void)inColor; (void)inNormal; (void)inMotion; (void)inDepth; (void)outColor;
    return JNI_FALSE;
#endif
}

// ─── JNI: destroyDenoiser ──────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_blockreality_api_client_render_rt_BRNRDNative_destroyDenoiser(
    JNIEnv* /*env*/, jclass /*cls*/,
    jlong denoiserHandle)
{
    NRDDenoiserInstance* inst = fromHandle(denoiserHandle);
    if (!inst) return;
    // Destructor calls nrd::DestroyDenoiser if NRD_AVAILABLE
    delete inst;
}
