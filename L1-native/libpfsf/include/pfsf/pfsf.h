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

/* v0.3d modular surface — each sub-header is pulled in via this umbrella
 * so legacy callers that `#include <pfsf/pfsf.h>` pick up the new symbols
 * automatically. Each module is independently consumable. */
#include "pfsf_version.h"
#include "pfsf_compute.h"
#include "pfsf_diagnostics.h"
#include "pfsf_extension.h"
#include "pfsf_plan.h"
#include "pfsf_trace.h"

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
 *  v0.3c — DirectByteBuffer zero-copy registration
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Primary voxel storage descriptors for an island. All pointers must be
 * 256-byte aligned and remain valid for the lifetime of the island.
 * Typically sourced from Java MemoryUtil.memAlignedAlloc-backed DBBs.
 */
typedef struct pfsf_island_buffers {
    void*   phi_addr;          /**< float32 × N            */
    int64_t phi_bytes;
    void*   source_addr;       /**< float32 × N (normalised) */
    int64_t source_bytes;
    void*   conductivity_addr; /**< float32 × 6N (SoA)     */
    int64_t conductivity_bytes;
    void*   voxel_type_addr;   /**< int32  × N             */
    int64_t voxel_type_bytes;
    void*   rcomp_addr;        /**< float32 × N (normalised) */
    int64_t rcomp_bytes;
    void*   rtens_addr;        /**< float32 × N (normalised) */
    int64_t rtens_bytes;
} pfsf_island_buffers;

/**
 * World-state lookup buffers — Java fills them with per-voxel values
 * (dirty ranges only) before each tick, C++ reads them directly.
 */
typedef struct pfsf_island_lookups {
    void*   material_id_addr;     /**< int32  × N */
    int64_t material_id_bytes;
    void*   anchor_bitmap_addr;   /**< int64  × N */
    int64_t anchor_bitmap_bytes;
    void*   fluid_pressure_addr;  /**< float32 × N */
    int64_t fluid_pressure_bytes;
    void*   curing_addr;          /**< float32 × N */
    int64_t curing_bytes;
} pfsf_island_lookups;

PFSF_API pfsf_result pfsf_register_island_buffers(pfsf_engine engine,
                                                   int32_t island_id,
                                                   const pfsf_island_buffers* bufs);

PFSF_API pfsf_result pfsf_register_island_lookups(pfsf_engine engine,
                                                   int32_t island_id,
                                                   const pfsf_island_lookups* lookups);

PFSF_API pfsf_result pfsf_register_stress_readback(pfsf_engine engine,
                                                    int32_t island_id,
                                                    void* addr,
                                                    int64_t bytes);

/**
 * Tick variant that consumes the pre-registered DBBs — no per-voxel
 * JNI traffic. @p failure_addr / @p failure_bytes is optional (NULL
 * skips failure reporting).
 */
PFSF_API pfsf_result pfsf_tick_dbb(pfsf_engine engine,
                                    const int32_t* dirty_island_ids,
                                    int32_t dirty_count,
                                    int64_t current_epoch,
                                    void* failure_addr,
                                    int64_t failure_bytes);

/**
 * Drain pending native → Java callback events into @p out_events. The
 * on-wire layout is {@c count} triples of int32: @c {kind, island_id,
 * payload_lo}. Returns the number of events written (≤ @p capacity / 3).
 */
PFSF_API int32_t pfsf_drain_callbacks(pfsf_engine engine,
                                       int32_t* out_events,
                                       int32_t capacity);

/* ═══════════════════════════════════════════════════════════════
 *  v0.3c — Sparse voxel re-upload (tick-time scatter)
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Obtain the CPU address + capacity of the island's persistent-mapped
 * sparse upload SSBO. The buffer is allocated lazily on first call
 * (host-visible, VMA-owned). Java wraps the returned address as a
 * DirectByteBuffer and writes up to {@code MAX_SPARSE_UPDATES_PER_TICK}
 * (512) packed 48-byte VoxelUpdate records into it each tick.
 *
 * After writing, Java calls {@link pfsf_notify_sparse_updates} to
 * dispatch the scatter pipeline.
 *
 * @param island_id  Target island.
 * @param out_addr   Receives the mapped host pointer. Must not be NULL.
 * @param out_bytes  Receives the buffer capacity in bytes
 *                   (= MAX_SPARSE_UPDATES_PER_TICK × 48). Must not be NULL.
 * @return PFSF_OK; PFSF_ERROR_INVALID_ARG if the island is unknown;
 *         PFSF_ERROR_VULKAN if allocation fails.
 */
PFSF_API pfsf_result pfsf_get_sparse_upload_buffer(pfsf_engine engine,
                                                    int32_t island_id,
                                                    void**  out_addr,
                                                    int64_t* out_bytes);

/**
 * Dispatch the sparse-scatter compute pipeline for @p update_count
 * records already packed into the island's sparse upload SSBO (see
 * {@link pfsf_get_sparse_upload_buffer}). Records the dispatch into
 * a transient command buffer, submits, and waits.
 *
 * {@code update_count} is clamped to MAX_SPARSE_UPDATES_PER_TICK on
 * the native side so Java bugs can't write past the buffer end.
 *
 * @return PFSF_OK; PFSF_ERROR_INVALID_ARG if the island is unknown;
 *         PFSF_ERROR_VULKAN on submit failure; PFSF_OK + 0-dispatch
 *         is returned silently when the scatter pipeline isn't ready.
 */
PFSF_API pfsf_result pfsf_notify_sparse_updates(pfsf_engine engine,
                                                 int32_t island_id,
                                                 int32_t update_count);

/* ═══════════════════════════════════════════════════════════════
 *  Version
 * ═══════════════════════════════════════════════════════════════ */

/** Returns version string, e.g. "0.1.0". */
PFSF_API const char* pfsf_version(void);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_H */
