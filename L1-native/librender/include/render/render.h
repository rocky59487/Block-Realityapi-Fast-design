/**
 * @file render.h
 * @brief libblockreality_render — Vulkan RT, BLAS/TLAS, ReSTIR/DDGI/ReLAX.
 *
 * Phase 1 (M4 skeleton): public C API + lifecycle stubs. The real BLAS
 * build, GPU BVH construction, RT pipeline, ReSTIR DI/GI dispatchers,
 * and denoiser passes land in subsequent M4 commits.
 */
#ifndef BR_RENDER_H
#define BR_RENDER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum render_result {
    RENDER_OK                = 0,
    RENDER_ERROR_VULKAN      = -1,
    RENDER_ERROR_NO_DEVICE   = -2,
    RENDER_ERROR_OUT_OF_VRAM = -3,
    RENDER_ERROR_INVALID_ARG = -4,
    RENDER_ERROR_NOT_INIT    = -5,
    RENDER_ERROR_NO_RT       = -6,  /**< RT unsupported on this GPU */
} render_result;

typedef enum render_tier {
    RENDER_TIER_FALLBACK = 0, /**< no RT — classic renderer stays live   */
    RENDER_TIER_ADA      = 1, /**< Ada: DDGI + ReLAX                     */
    RENDER_TIER_BLACKWELL= 2, /**< Blackwell: ReSTIR DI+GI + cluster AS  */
} render_tier;

typedef struct render_config {
    int32_t     width;
    int32_t     height;
    int64_t     vram_budget_bytes;
    render_tier tier_override; /**< 0 = auto-detect */
    bool        enable_restir;
    bool        enable_ddgi;
    bool        enable_relax;
} render_config;

typedef void* render_engine;

render_engine render_create(const render_config* cfg);
render_result render_init(render_engine engine);
void          render_shutdown(render_engine engine);
void          render_destroy(render_engine engine);
bool          render_is_available(render_engine engine);
render_tier   render_active_tier(render_engine engine);

/** Update the camera UBO via a pre-registered DBB (256-byte aligned). */
render_result render_update_camera_dbb(render_engine engine, void* addr, int64_t bytes);

/** Submit one frame. Interop handles come from the Java GL swapchain. */
render_result render_submit_frame(render_engine engine, int64_t frame_index);

const char* render_version(void);

#ifdef __cplusplus
}
#endif
#endif /* BR_RENDER_H */
