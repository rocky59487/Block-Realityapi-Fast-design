/**
 * @file render_api.cpp
 * @brief libblockreality_render C API — M4 skeleton stubs.
 */
#include "render/render.h"

#include <cstdlib>
#include <new>

namespace {

struct RenderEngine {
    render_config config{};
    render_tier   tier        = RENDER_TIER_FALLBACK;
    bool          initialized = false;
};

inline RenderEngine* as(render_engine h) { return static_cast<RenderEngine*>(h); }

} // namespace

extern "C" {

render_engine render_create(const render_config* cfg) {
    RenderEngine* e = new (std::nothrow) RenderEngine();
    if (e == nullptr) return nullptr;
    if (cfg != nullptr) {
        e->config = *cfg;
        // Preserve the caller's intent verbatim. Any mapping from the
        // auto-detect sentinel (FALLBACK/0) to a concrete tier is the
        // job of render_init()'s capability probe — not render_create().
        // Collapsing here would silently discard the "auto-detect" signal
        // and permanently bind the engine to FALLBACK even on RT-capable
        // hardware.
        e->tier = cfg->tier_override;
    } else {
        e->config.width  = 1920;
        e->config.height = 1080;
        e->config.vram_budget_bytes = 1024LL * 1024 * 1024;
        e->tier = RENDER_TIER_FALLBACK;
    }
    return e;
}

render_result render_init(render_engine engine) {
    if (!engine) return RENDER_ERROR_INVALID_ARG;
    // M4 skeleton: the real RT capability probe (Vulkan device caps →
    // Ada / Blackwell tier resolution) lives in M4b alongside the DDGI /
    // ReSTIR pipelines. Until it lands, report honestly: the native
    // renderer cannot verify RT support, so we refuse init with
    // RENDER_ERROR_NO_RT. NativeRenderRuntime.init() handles this by
    // logging a warning and falling back to the classic Java renderer.
    // Previously this stub always returned RENDER_OK and left tier at
    // whatever the caller passed — which let Java believe it had live
    // ADA / BLACKWELL even on hardware with no RT extensions.
    RenderEngine* e = as(engine);
    e->initialized = false;
    e->tier        = RENDER_TIER_FALLBACK;
    return RENDER_ERROR_NO_RT;
}

void render_shutdown(render_engine engine) { if (engine) as(engine)->initialized = false; }

void render_destroy(render_engine engine) {
    if (engine) { render_shutdown(engine); delete as(engine); }
}

bool render_is_available(render_engine engine) {
    return engine != nullptr && as(engine)->initialized;
}

render_tier render_active_tier(render_engine engine) {
    return engine ? as(engine)->tier : RENDER_TIER_FALLBACK;
}

render_result render_update_camera_dbb(render_engine engine, void* addr, int64_t bytes) {
    if (!engine || !addr || bytes <= 0) return RENDER_ERROR_INVALID_ARG;
    if (!as(engine)->initialized) return RENDER_ERROR_NOT_INIT;
    // TODO (M4b): memcpy into the mapped VMA camera UBO.
    return RENDER_OK;
}

render_result render_submit_frame(render_engine engine, int64_t /*frame*/) {
    if (!engine) return RENDER_ERROR_INVALID_ARG;
    if (!as(engine)->initialized) return RENDER_ERROR_NOT_INIT;
    return RENDER_OK;
}

const char* render_version(void) { return "0.1.0"; }

} // extern "C"
