/**
 * @file fluid.h
 * @brief libfluid — Block Reality PFSF-Fluid GPU solver public C API.
 *
 * Preserves the IFluidManager SPI contract from Java:
 *   - init()/tick()/shutdown()
 *   - per-island fluid field (pressure, velocity, flux) exposed via DBB
 *   - 1-tick lag against the PFSF structure solver (design invariant)
 */
#ifndef FLUID_H
#define FLUID_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum fluid_result {
    FLUID_OK                =  0,
    FLUID_ERROR_VULKAN      = -1,
    FLUID_ERROR_NO_DEVICE   = -2,
    FLUID_ERROR_OUT_OF_VRAM = -3,
    FLUID_ERROR_INVALID_ARG = -4,
    FLUID_ERROR_NOT_INIT    = -5,
} fluid_result;

typedef struct fluid_config {
    int32_t max_island_size;   /**< voxel count */
    int32_t tick_budget_ms;
    int64_t vram_budget_bytes;
    bool    enable_surface_tension;
    bool    enable_coupling;   /**< structure ↔ fluid pressure/force */
} fluid_config;

typedef void* fluid_engine;

typedef struct fluid_island_buffers {
    void*   pressure_addr;     /**< float32 × N */
    int64_t pressure_bytes;
    void*   velocity_addr;     /**< float32 × 3N (AoS xyz) */
    int64_t velocity_bytes;
    void*   flux_addr;         /**< float32 × 6N (SoA per direction) */
    int64_t flux_bytes;
    void*   level_set_addr;    /**< float32 × N — signed distance */
    int64_t level_set_bytes;
} fluid_island_buffers;

/* Lifecycle */
fluid_engine fluid_create(const fluid_config* cfg);
fluid_result fluid_init(fluid_engine engine);
void         fluid_shutdown(fluid_engine engine);
void         fluid_destroy(fluid_engine engine);
bool         fluid_is_available(fluid_engine engine);

/* Island lifecycle */
fluid_result fluid_add_island(fluid_engine engine, int32_t island_id,
                               int32_t lx, int32_t ly, int32_t lz);
void         fluid_remove_island(fluid_engine engine, int32_t island_id);

/* Zero-copy buffer registration (DirectByteBuffer path) */
fluid_result fluid_register_island_buffers(fluid_engine engine,
                                            int32_t island_id,
                                            const fluid_island_buffers* bufs);

/* Main tick — runs pressure solve + advection for every registered island. */
fluid_result fluid_tick(fluid_engine engine,
                         const int32_t* dirty_island_ids, int32_t dirty_count,
                         int64_t current_epoch);

const char* fluid_version(void);

#ifdef __cplusplus
}
#endif
#endif /* FLUID_H */
