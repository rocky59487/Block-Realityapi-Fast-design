/**
 * @file pfsf.h
 * @brief libpfsf — Block Reality PFSF physics solver public C API.
 *
 * Thread-safety: all functions must be called from the same thread
 * (the "physics thread"), except pfsf_get_stats() which is safe to
 * call from any thread.
 *
 * Lifecycle:
 *   pfsf_create()  → pfsf_init()  → [pfsf_tick() loop] → pfsf_shutdown() → pfsf_destroy()
 *
 * @see pfsf_types.h for type definitions
 */
#ifndef PFSF_H
#define PFSF_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════════
 *  Lifecycle
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Create an engine instance. Does NOT initialize Vulkan yet.
 *
 * @param config  Engine configuration (NULL for defaults).
 * @return Opaque engine handle, or NULL on allocation failure.
 */
PFSF_API pfsf_engine pfsf_create(const pfsf_config* config);

/**
 * Initialize Vulkan compute context and shader pipelines.
 * Must be called once after pfsf_create().
 *
 * @return PFSF_OK on success, PFSF_ERROR_VULKAN or PFSF_ERROR_NO_DEVICE on failure.
 */
PFSF_API pfsf_result pfsf_init(pfsf_engine engine);

/**
 * Shut down the engine: destroy pipelines, free all GPU buffers.
 * Safe to call multiple times. After shutdown, pfsf_init() may be
 * called again to re-initialize.
 */
PFSF_API void pfsf_shutdown(pfsf_engine engine);

/**
 * Destroy the engine instance and free all memory.
 * Calls pfsf_shutdown() if not yet shut down.
 *
 * @param engine  Handle to destroy (NULL is safe no-op).
 */
PFSF_API void pfsf_destroy(pfsf_engine engine);

/**
 * Query whether the engine is initialized and GPU-available.
 */
PFSF_API bool pfsf_is_available(pfsf_engine engine);

/**
 * Query engine statistics (thread-safe).
 */
PFSF_API pfsf_result pfsf_get_stats(pfsf_engine engine, pfsf_stats* out);

/* ═══════════════════════════════════════════════════════════════
 *  Configuration (call before or between ticks)
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Set the material lookup callback. Called during tick to query
 * material properties for each voxel.
 *
 * @param fn        Callback function.
 * @param user_data Opaque pointer passed to every callback invocation.
 */
PFSF_API void pfsf_set_material_lookup(pfsf_engine engine,
                                        pfsf_material_fn fn, void* user_data);

PFSF_API void pfsf_set_anchor_lookup(pfsf_engine engine,
                                      pfsf_anchor_fn fn, void* user_data);

PFSF_API void pfsf_set_fill_ratio_lookup(pfsf_engine engine,
                                          pfsf_fill_ratio_fn fn, void* user_data);

PFSF_API void pfsf_set_curing_lookup(pfsf_engine engine,
                                      pfsf_curing_fn fn, void* user_data);

/**
 * Set global wind vector (world-space). NULL or zero vector = no wind.
 */
PFSF_API void pfsf_set_wind(pfsf_engine engine, const pfsf_vec3* wind);

/* ═══════════════════════════════════════════════════════════════
 *  Island management
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Register a new structure island. Allocates GPU buffers.
 *
 * @param desc  Island descriptor (id, origin, dimensions).
 * @return PFSF_OK, PFSF_ERROR_OUT_OF_VRAM, or PFSF_ERROR_ISLAND_FULL.
 */
PFSF_API pfsf_result pfsf_add_island(pfsf_engine engine,
                                      const pfsf_island_desc* desc);

/**
 * Remove an island and free its GPU buffers.
 */
PFSF_API void pfsf_remove_island(pfsf_engine engine, int32_t island_id);

/* ═══════════════════════════════════════════════════════════════
 *  Sparse dirty notification
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Notify a single voxel change (block place/break).
 * Queues a sparse GPU update for the next tick.
 *
 * @param island_id  Island containing this voxel.
 * @param update     Voxel update descriptor (NULL material = air).
 */
PFSF_API pfsf_result pfsf_notify_block_change(pfsf_engine engine,
                                               int32_t island_id,
                                               const pfsf_voxel_update* update);

/**
 * Mark an entire island for full rebuild on next tick.
 */
PFSF_API void pfsf_mark_full_rebuild(pfsf_engine engine, int32_t island_id);

/* ═══════════════════════════════════════════════════════════════
 *  Main tick loop
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Run one physics tick. Dispatches GPU compute, reads back failures.
 *
 * @param dirty_island_ids  Array of island IDs that changed this epoch.
 * @param dirty_count       Number of dirty islands.
 * @param current_epoch     Monotonic epoch counter for change detection.
 * @param result            Output: failure events detected (caller allocates).
 *                          May be NULL if caller doesn't need failure info.
 * @return PFSF_OK on success, error code on GPU failure.
 */
PFSF_API pfsf_result pfsf_tick(pfsf_engine engine,
                                const int32_t* dirty_island_ids,
                                int32_t dirty_count,
                                int64_t current_epoch,
                                pfsf_tick_result* result);

/* ═══════════════════════════════════════════════════════════════
 *  Stress field readback
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Read back the stress utilization ratio for an island.
 *
 * @param island_id     Island to query.
 * @param out_stress    Caller-allocated float array of size N (= lx*ly*lz).
 * @param capacity      Size of out_stress array.
 * @param out_count     Number of values written (out).
 * @return PFSF_OK, PFSF_ERROR_INVALID_ARG if island not found.
 */
PFSF_API pfsf_result pfsf_read_stress(pfsf_engine engine,
                                       int32_t island_id,
                                       float* out_stress,
                                       int32_t capacity,
                                       int32_t* out_count);

/* ═══════════════════════════════════════════════════════════════
 *  Version
 * ═══════════════════════════════════════════════════════════════ */

/** Returns version string, e.g. "0.1.0". */
PFSF_API const char* pfsf_version(void);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_H */
