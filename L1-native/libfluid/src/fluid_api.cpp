/**
 * @file fluid_api.cpp
 * @brief libfluid C API — Phase-1 stubs. Real GPU solver lands in M3.
 */
#include "fluid/fluid.h"

#include <cstdlib>
#include <cstring>
#include <new>

namespace {

struct FluidEngine {
    fluid_config config{};
    bool         initialized = false;
};

inline FluidEngine* as(fluid_engine h) { return static_cast<FluidEngine*>(h); }

} // namespace

extern "C" {

fluid_engine fluid_create(const fluid_config* cfg) {
    FluidEngine* e = new (std::nothrow) FluidEngine();
    if (e == nullptr) return nullptr;
    if (cfg != nullptr) e->config = *cfg;
    else {
        e->config.max_island_size    = 50000;
        e->config.tick_budget_ms     = 8;
        e->config.vram_budget_bytes  = 128LL * 1024 * 1024;
        e->config.enable_surface_tension = false;
        e->config.enable_coupling    = true;
    }
    return e;
}

fluid_result fluid_init(fluid_engine engine) {
    if (!engine) return FLUID_ERROR_INVALID_ARG;
    as(engine)->initialized = true;
    return FLUID_OK;
}

void fluid_shutdown(fluid_engine engine) {
    if (engine) as(engine)->initialized = false;
}

void fluid_destroy(fluid_engine engine) {
    if (engine) { fluid_shutdown(engine); delete as(engine); }
}

bool fluid_is_available(fluid_engine engine) {
    return engine != nullptr && as(engine)->initialized;
}

fluid_result fluid_add_island(fluid_engine engine, int32_t /*id*/,
                               int32_t /*lx*/, int32_t /*ly*/, int32_t /*lz*/) {
    if (!engine) return FLUID_ERROR_INVALID_ARG;
    return FLUID_OK;
}

void fluid_remove_island(fluid_engine /*engine*/, int32_t /*id*/) {}

fluid_result fluid_register_island_buffers(fluid_engine engine,
                                            int32_t /*id*/,
                                            const fluid_island_buffers* bufs) {
    if (!engine || !bufs) return FLUID_ERROR_INVALID_ARG;
    if (!bufs->pressure_addr || !bufs->velocity_addr ||
        !bufs->flux_addr     || !bufs->level_set_addr) {
        return FLUID_ERROR_INVALID_ARG;
    }
    return FLUID_OK;
}

fluid_result fluid_tick(fluid_engine engine,
                         const int32_t* /*dirty*/, int32_t /*count*/,
                         int64_t /*epoch*/) {
    if (!engine) return FLUID_ERROR_INVALID_ARG;
    if (!as(engine)->initialized) return FLUID_ERROR_NOT_INIT;
    return FLUID_OK;
}

const char* fluid_version(void) { return "0.1.0"; }

} // extern "C"
