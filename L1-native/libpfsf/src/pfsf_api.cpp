/**
 * @file pfsf_api.cpp
 * @brief C API bridge — thin wrapper over PFSFEngine.
 */
#include <pfsf/pfsf.h>
#include "pfsf_engine.h"

using namespace pfsf;

/* ═══ Helpers ═══ */

static inline PFSFEngine* E(pfsf_engine h) {
    return reinterpret_cast<PFSFEngine*>(h);
}

#define CHECK_ENGINE(h) \
    if (!(h)) return PFSF_ERROR_INVALID_ARG; \
    if (!E(h)->isAvailable()) return PFSF_ERROR_NOT_INIT;

/* ═══ Lifecycle ═══ */

pfsf_engine pfsf_create(const pfsf_config* config) {
    pfsf_config cfg{};
    if (config) {
        cfg = *config;
    } else {
        cfg.max_island_size    = 50000;
        cfg.tick_budget_ms     = 8;
        cfg.vram_budget_bytes  = 512LL * 1024 * 1024;
        cfg.enable_phase_field = true;
        cfg.enable_multigrid   = true;
    }

    auto* engine = new (std::nothrow) PFSFEngine(cfg);
    return reinterpret_cast<pfsf_engine>(engine);
}

pfsf_result pfsf_init(pfsf_engine engine) {
    if (!engine) return PFSF_ERROR_INVALID_ARG;
    return E(engine)->init();
}

void pfsf_shutdown(pfsf_engine engine) {
    if (engine) E(engine)->shutdown();
}

void pfsf_destroy(pfsf_engine engine) {
    if (!engine) return;
    E(engine)->shutdown();
    delete E(engine);
}

bool pfsf_is_available(pfsf_engine engine) {
    return engine && E(engine)->isAvailable();
}

pfsf_result pfsf_get_stats(pfsf_engine engine, pfsf_stats* out) {
    CHECK_ENGINE(engine);
    return E(engine)->getStats(out);
}

/* ═══ Configuration ═══ */

void pfsf_set_material_lookup(pfsf_engine engine,
                               pfsf_material_fn fn, void* user_data) {
    if (engine) E(engine)->setMaterialLookup(fn, user_data);
}

void pfsf_set_anchor_lookup(pfsf_engine engine,
                              pfsf_anchor_fn fn, void* user_data) {
    if (engine) E(engine)->setAnchorLookup(fn, user_data);
}

void pfsf_set_fill_ratio_lookup(pfsf_engine engine,
                                  pfsf_fill_ratio_fn fn, void* user_data) {
    if (engine) E(engine)->setFillRatioLookup(fn, user_data);
}

void pfsf_set_curing_lookup(pfsf_engine engine,
                              pfsf_fill_ratio_fn fn, void* user_data) {
    if (engine) E(engine)->setCuringLookup(fn, user_data);
}

void pfsf_set_wind(pfsf_engine engine, const pfsf_vec3* wind) {
    if (engine) E(engine)->setWind(wind);
}

/* ═══ Island management ═══ */

pfsf_result pfsf_add_island(pfsf_engine engine, const pfsf_island_desc* desc) {
    CHECK_ENGINE(engine);
    return E(engine)->addIsland(desc);
}

void pfsf_remove_island(pfsf_engine engine, int32_t island_id) {
    if (engine) E(engine)->removeIsland(island_id);
}

/* ═══ Sparse notification ═══ */

pfsf_result pfsf_notify_block_change(pfsf_engine engine,
                                      int32_t island_id,
                                      const pfsf_voxel_update* update) {
    CHECK_ENGINE(engine);
    return E(engine)->notifyBlockChange(island_id, update);
}

void pfsf_mark_full_rebuild(pfsf_engine engine, int32_t island_id) {
    if (engine) E(engine)->markFullRebuild(island_id);
}

/* ═══ Tick ═══ */

pfsf_result pfsf_tick(pfsf_engine engine,
                       const int32_t* dirty_island_ids,
                       int32_t dirty_count,
                       int64_t current_epoch,
                       pfsf_tick_result* result) {
    CHECK_ENGINE(engine);
    return E(engine)->tick(dirty_island_ids, dirty_count, current_epoch, result);
}

/* ═══ Stress readback ═══ */

pfsf_result pfsf_read_stress(pfsf_engine engine,
                              int32_t island_id,
                              float* out_stress,
                              int32_t capacity,
                              int32_t* out_count) {
    CHECK_ENGINE(engine);
    return E(engine)->readStress(island_id, out_stress, capacity, out_count);
}

/* ═══════════════════════════════════════════════════════════════
 *  v0.3c — DirectByteBuffer zero-copy path (Phase-2 stubs)
 * ═══════════════════════════════════════════════════════════════
 *  Full implementations land alongside the solver port to libbr_core;
 *  for now these accept + remember the buffer addresses so the
 *  Java-side ABI is stable and smoke tests can wire end-to-end.
 */

pfsf_result pfsf_register_island_buffers(pfsf_engine engine,
                                          int32_t island_id,
                                          const pfsf_island_buffers* bufs) {
    CHECK_ENGINE(engine);
    return E(engine)->registerIslandBuffers(island_id, bufs);
}

pfsf_result pfsf_register_island_lookups(pfsf_engine engine,
                                          int32_t island_id,
                                          const pfsf_island_lookups* lookups) {
    CHECK_ENGINE(engine);
    return E(engine)->registerIslandLookups(island_id, lookups);
}

pfsf_result pfsf_register_stress_readback(pfsf_engine engine,
                                           int32_t island_id,
                                           void* addr,
                                           int64_t bytes) {
    CHECK_ENGINE(engine);
    return E(engine)->registerStressReadback(island_id, addr, bytes);
}

pfsf_result pfsf_tick_dbb(pfsf_engine engine,
                           const int32_t* dirty_island_ids,
                           int32_t dirty_count,
                           int64_t current_epoch,
                           void* failure_addr,
                           int64_t failure_bytes) {
    CHECK_ENGINE(engine);
    return E(engine)->tickDbb(dirty_island_ids, dirty_count, current_epoch,
                               failure_addr, failure_bytes);
}

int32_t pfsf_drain_callbacks(pfsf_engine engine,
                              int32_t* out_events,
                              int32_t capacity) {
    if (!engine || !out_events || capacity <= 0) return 0;
    if (!E(engine)->isAvailable()) return 0;
    return E(engine)->drainCallbacks(out_events, capacity);
}

/* ═══ Sparse voxel re-upload (v0.3c M2n) ═══ */

pfsf_result pfsf_get_sparse_upload_buffer(pfsf_engine engine,
                                           int32_t island_id,
                                           void** out_addr,
                                           int64_t* out_bytes) {
    CHECK_ENGINE(engine);
    return E(engine)->getSparseUploadBuffer(island_id, out_addr, out_bytes);
}

pfsf_result pfsf_notify_sparse_updates(pfsf_engine engine,
                                        int32_t island_id,
                                        int32_t update_count) {
    CHECK_ENGINE(engine);
    return E(engine)->notifySparseUpdates(island_id, update_count);
}

/* ═══ Version ═══ */

const char* pfsf_version(void) {
    return "0.1.0";
}
