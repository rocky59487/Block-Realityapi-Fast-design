/**
 * @file pfsf_engine.cpp
 * @brief PFSF engine implementation — lifecycle, tick, callbacks.
 */
#include "pfsf_engine.h"
#include "core/constants.h"
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <algorithm>
#include <vector>

namespace pfsf {

PFSFEngine::PFSFEngine(const pfsf_config& config)
    : config_(config) {}

PFSFEngine::~PFSFEngine() {
    shutdown();
}

// ═══ Lifecycle ═══

pfsf_result PFSFEngine::init() {
    if (available_) return PFSF_OK;

    vk_ = std::make_unique<VulkanContext>();
    if (!vk_->init()) {
        vk_.reset();
        return PFSF_ERROR_NO_DEVICE;
    }

    buffers_ = std::make_unique<BufferManager>(*vk_, config_.enable_phase_field);

    // Create descriptor pool
    descPool_ = vk_->createDescriptorPool(2048, 8192);
    if (descPool_ == VK_NULL_HANDLE) {
        shutdown();
        return PFSF_ERROR_VULKAN;
    }

    // Create solver pipelines
    jacobi_     = std::make_unique<JacobiSolver>(*vk_);
    vcycle_     = std::make_unique<VCycleSolver>(*vk_);
    phaseField_ = std::make_unique<PhaseFieldSolver>(*vk_);
    failure_    = std::make_unique<FailureScan>(*vk_);
    pcg_        = std::make_unique<PCGSolver>(*vk_);
    sparse_     = std::make_unique<SparseScatterSolver>(*vk_);

    // RBGS is the critical-path smoother — required.
    if (!jacobi_->createPipeline()) {
        shutdown();
        return PFSF_ERROR_VULKAN;
    }
    // The rest are soft-optional: failing to load one shader blob should
    // degrade, not brick, the runtime. Dispatcher checks isReady() per call.
    vcycle_->createPipeline();
    phaseField_->createPipeline();
    failure_->createPipeline();
    pcg_->createPipelines();
    sparse_->createPipeline();

    dispatcher_ = std::make_unique<Dispatcher>(
        *vk_, *jacobi_, *vcycle_, *phaseField_, *failure_, *pcg_, *sparse_);

    available_ = true;
    fprintf(stderr, "[libpfsf] Engine initialized (%s, VRAM: %lld MB)\n",
            vk_->deviceName().c_str(),
            (long long)(vk_->deviceLocalBytes() / (1024 * 1024)));
    return PFSF_OK;
}

void PFSFEngine::shutdown() {
    if (!vk_) return;

    dispatcher_.reset();
    sparse_.reset();
    pcg_.reset();
    failure_.reset();
    phaseField_.reset();
    vcycle_.reset();
    jacobi_.reset();
    buffers_.reset();

    if (descPool_ != VK_NULL_HANDLE && vk_->device() != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(vk_->device(), descPool_, nullptr);
        descPool_ = VK_NULL_HANDLE;
    }

    vk_->shutdown();
    vk_.reset();
    available_ = false;
    fprintf(stderr, "[libpfsf] Engine shut down\n");
}

// ═══ Stats ═══

pfsf_result PFSFEngine::getStats(pfsf_stats* out) const {
    if (!out) return PFSF_ERROR_INVALID_ARG;

    std::lock_guard<std::mutex> lock(statsMtx_);
    out->island_count     = buffers_ ? buffers_->count() : 0;
    out->total_voxels     = buffers_ ? buffers_->totalVoxels() : 0;
    out->vram_budget_bytes = config_.vram_budget_bytes;
    out->vram_used_bytes  = 0;  // Phase 3: track actual usage
    out->last_tick_ms     = lastTickMs_;
    return PFSF_OK;
}

// ═══ Configuration ═══

void PFSFEngine::setMaterialLookup(pfsf_material_fn fn, void* ud) {
    materialFn_ = fn; materialUD_ = ud;
}
void PFSFEngine::setAnchorLookup(pfsf_anchor_fn fn, void* ud) {
    anchorFn_ = fn; anchorUD_ = ud;
}
void PFSFEngine::setFillRatioLookup(pfsf_fill_ratio_fn fn, void* ud) {
    fillRatioFn_ = fn; fillRatioUD_ = ud;
}
void PFSFEngine::setCuringLookup(pfsf_curing_fn fn, void* ud) {
    curingFn_ = fn; curingUD_ = ud;
}
void PFSFEngine::setWind(const pfsf_vec3* wind) {
    wind_ = wind ? *wind : pfsf_vec3{0, 0, 0};
}

// ═══ Island management ═══

pfsf_result PFSFEngine::addIsland(const pfsf_island_desc* desc) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!desc) return PFSF_ERROR_INVALID_ARG;

    // ★ Guard individual dimensions before int64 multiplication to prevent UB
    if (desc->lx <= 0 || desc->ly <= 0 || desc->lz <= 0) return PFSF_ERROR_ISLAND_FULL;
    // If any single dimension already exceeds max_island_size, the product trivially does too.
    if (static_cast<int64_t>(desc->lx) > config_.max_island_size ||
        static_cast<int64_t>(desc->ly) > config_.max_island_size ||
        static_cast<int64_t>(desc->lz) > config_.max_island_size) {
        return PFSF_ERROR_ISLAND_FULL;
    }
    int64_t n64 = static_cast<int64_t>(desc->lx) * desc->ly * desc->lz;
    if (n64 > config_.max_island_size) return PFSF_ERROR_ISLAND_FULL;

    IslandBuffer* buf = buffers_->getOrCreate(*desc);
    return buf ? PFSF_OK : PFSF_ERROR_OUT_OF_VRAM;
}

void PFSFEngine::removeIsland(int32_t island_id) {
    if (buffers_) buffers_->remove(island_id);
}

// ═══ Sparse notification ═══

pfsf_result PFSFEngine::notifyBlockChange(int32_t island_id,
                                           const pfsf_voxel_update* update) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!update) return PFSF_ERROR_INVALID_ARG;

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf) return PFSF_ERROR_INVALID_ARG;

    if (update->flat_index < 0 || static_cast<int64_t>(update->flat_index) >= buf->N()) {
        return PFSF_ERROR_INVALID_ARG;
    }

    // Phase 3: queue sparse update for GPU scatter
    buf->markDirty();
    return PFSF_OK;
}

void PFSFEngine::markFullRebuild(int32_t island_id) {
    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (buf) buf->markDirty();
}

// ═══ DBB zero-copy registration ═══

pfsf_result PFSFEngine::registerIslandBuffers(int32_t island_id,
                                               const pfsf_island_buffers* bufs) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!bufs) return PFSF_ERROR_INVALID_ARG;
    if (!bufs->phi_addr || !bufs->source_addr || !bufs->conductivity_addr ||
        !bufs->voxel_type_addr || !bufs->rcomp_addr || !bufs->rtens_addr ||
        !bufs->max_phi_addr) {
        return PFSF_ERROR_INVALID_ARG;
    }

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf || !buf->allocated) return PFSF_ERROR_INVALID_ARG;

    // ★ Size validation — uploadFromHosts() silently truncates with
    //   std::min(host_bytes, expected_bytes), so an undersized DBB leaves
    //   the tail of the device buffer uninitialised while solvers and
    //   failure_scan still read the full N/6N extent. Reject here instead
    //   to honour the documented ERROR_INVALID_ARG contract — mirrors
    //   registerIslandLookups below.
    const std::int64_t n   = buf->N();
    const std::int64_t i4n = n * 4;            // float / int32
    const std::int64_t i1n = n * 1;            // uint8 (voxel_type)
    const std::int64_t i24n = n * 24;          // SoA-6 conductivity
    if (bufs->phi_bytes          < i4n  ||
        bufs->source_bytes       < i4n  ||
        bufs->conductivity_bytes < i24n ||
        bufs->voxel_type_bytes   < i1n  ||
        bufs->rcomp_bytes        < i4n  ||
        bufs->rtens_bytes        < i4n  ||
        bufs->max_phi_bytes      < i4n) {
        return PFSF_ERROR_INVALID_ARG;
    }

    buf->hosts.phi                = bufs->phi_addr;
    buf->hosts.phi_bytes          = bufs->phi_bytes;
    buf->hosts.source             = bufs->source_addr;
    buf->hosts.source_bytes       = bufs->source_bytes;
    buf->hosts.conductivity       = bufs->conductivity_addr;
    buf->hosts.conductivity_bytes = bufs->conductivity_bytes;
    buf->hosts.voxel_type         = bufs->voxel_type_addr;
    buf->hosts.voxel_type_bytes   = bufs->voxel_type_bytes;
    buf->hosts.rcomp              = bufs->rcomp_addr;
    buf->hosts.rcomp_bytes        = bufs->rcomp_bytes;
    buf->hosts.rtens              = bufs->rtens_addr;
    buf->hosts.rtens_bytes        = bufs->rtens_bytes;
    buf->hosts.max_phi            = bufs->max_phi_addr;
    buf->hosts.max_phi_bytes      = bufs->max_phi_bytes;
    buf->hosts.registered         = true;

    if (!buf->uploadFromHosts(*vk_)) {
        return PFSF_ERROR_VULKAN;
    }
    buf->markDirty();   // first tick runs the solver on freshly-uploaded data
    return PFSF_OK;
}

pfsf_result PFSFEngine::registerIslandLookups(int32_t island_id,
                                               const pfsf_island_lookups* lookups) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!lookups) return PFSF_ERROR_INVALID_ARG;
    if (!lookups->material_id_addr || !lookups->anchor_bitmap_addr ||
        !lookups->fluid_pressure_addr || !lookups->curing_addr) {
        return PFSF_ERROR_INVALID_ARG;
    }

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf || !buf->allocated) return PFSF_ERROR_INVALID_ARG;

    // ★ Sanity-check sizes — each lookup table is one entry per voxel
    // except anchor_bitmap (int64 per voxel). Under-sized DBBs would
    // later cause native reads to stray past the end of JVM-owned memory.
    const std::int64_t n   = buf->N();
    const std::int64_t i4n = n * 4;
    const std::int64_t i8n = n * 8;
    if (lookups->material_id_bytes    < i4n ||
        lookups->anchor_bitmap_bytes  < i8n ||
        lookups->fluid_pressure_bytes < i4n ||
        lookups->curing_bytes         < i4n) {
        return PFSF_ERROR_INVALID_ARG;
    }

    buf->hosts.material_id         = lookups->material_id_addr;
    buf->hosts.material_id_bytes   = lookups->material_id_bytes;
    buf->hosts.anchor_bitmap       = lookups->anchor_bitmap_addr;
    buf->hosts.anchor_bitmap_bytes = lookups->anchor_bitmap_bytes;
    buf->hosts.fluid_pressure      = lookups->fluid_pressure_addr;
    buf->hosts.fluid_pressure_bytes= lookups->fluid_pressure_bytes;
    buf->hosts.curing              = lookups->curing_addr;
    buf->hosts.curing_bytes        = lookups->curing_bytes;
    buf->hosts.lookups_registered  = true;
    return PFSF_OK;
}

pfsf_result PFSFEngine::registerStressReadback(int32_t island_id,
                                                void* addr, int64_t bytes) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!addr || bytes <= 0) return PFSF_ERROR_INVALID_ARG;

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf || !buf->allocated) return PFSF_ERROR_INVALID_ARG;

    buf->hosts.stress_out   = addr;
    buf->hosts.stress_bytes = bytes;
    return PFSF_OK;
}

// ═══ Sparse scatter (v0.3c M2m) ═══

pfsf_result PFSFEngine::getSparseUploadBuffer(int32_t island_id,
                                                void** outAddr,
                                                int64_t* outBytes) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!outAddr || !outBytes) return PFSF_ERROR_INVALID_ARG;

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf || !buf->allocated) return PFSF_ERROR_INVALID_ARG;

    if (!buf->hasSparseUpload()) {
        if (!buf->allocateSparseUpload(*vk_)) return PFSF_ERROR_VULKAN;
    }

    *outAddr  = buf->sparse_upload_mapped;
    *outBytes = static_cast<int64_t>(buf->sparse_upload_capacity) *
                IslandBuffer::SPARSE_RECORD_BYTES;
    return PFSF_OK;
}

pfsf_result PFSFEngine::notifySparseUpdates(int32_t island_id, int32_t updateCount) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (updateCount <= 0) return PFSF_OK;        // nothing to do, trivially success

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf || !buf->allocated) return PFSF_ERROR_INVALID_ARG;
    if (!buf->hasSparseUpload())  return PFSF_ERROR_INVALID_ARG;
    if (!dispatcher_ || descPool_ == VK_NULL_HANDLE) return PFSF_ERROR_NOT_INIT;

    VkCommandBuffer cmd = vk_->allocCmdBuffer();
    if (cmd == VK_NULL_HANDLE) return PFSF_ERROR_VULKAN;

    const bool recorded =
        dispatcher_->recordSparseScatter(cmd, *buf, descPool_, updateCount);
    if (!recorded) {
        // Pipeline not ready (shader blob missing) — soft-fail with OK so the
        // Java side can fall back to a full upload path without error noise.
        vk_->submitAndWait(cmd);
        vkResetDescriptorPool(vk_->device(), descPool_, 0);
        return PFSF_OK;
    }

    vk_->submitAndWait(cmd);
    vkResetDescriptorPool(vk_->device(), descPool_, 0);
    // Scattered writes invalidate the resident phi field — a subsequent tick
    // will re-solve; mark dirty so the tick loop will actually run it.
    buf->markDirty();
    return PFSF_OK;
}

// ═══ Tick ═══

pfsf_result PFSFEngine::tick(const int32_t* dirty_ids, int32_t dirty_count,
                              int64_t epoch, pfsf_tick_result* result) {
    return tickImpl(dirty_ids, dirty_count, epoch, result, nullptr, 0);
}

pfsf_result PFSFEngine::tickDbb(const int32_t* dirty_ids, int32_t dirty_count,
                                 int64_t epoch, void* failure_addr,
                                 int64_t failure_bytes) {
    // Pre-zero the failure header so Java sees a coherent count=0 even
    // when the tick early-returns (shutdown, no dirty islands, partial
    // GPU init). This matches the NativePFSFBridge contract.
    if (failure_addr && failure_bytes >= static_cast<int64_t>(sizeof(int32_t))) {
        *static_cast<int32_t*>(failure_addr) = 0;
    }
    return tickImpl(dirty_ids, dirty_count, epoch, nullptr,
                    failure_addr, failure_bytes);
}

pfsf_result PFSFEngine::tickImpl(const int32_t* dirty_ids, int32_t dirty_count,
                                  int64_t /*epoch*/, pfsf_tick_result* result,
                                  void* failure_addr, int64_t failure_bytes) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (dirty_count > 0 && !dirty_ids) return PFSF_ERROR_INVALID_ARG;

    auto t0 = std::chrono::steady_clock::now();

    if (result) result->count = 0;

    // Only drain failures when the DBB is large enough for the header +
    // at least one tuple (4 B hdr + 16 B tuple = 20 B).
    const bool drain_failures =
        (failure_addr != nullptr) && (failure_bytes >= 20);

    for (int32_t i = 0; i < dirty_count; i++) {
        int32_t id = dirty_ids[i];
        IslandBuffer* buf = buffers_->get(id);
        if (!buf || !buf->dirty) continue;

        // Zero-copy contract: registered DBBs are the authoritative copy —
        // Java edits phi/source/conductivity/voxelType/rcomp/rtens/maxPhi
        // in place and marks the island dirty. Push those edits to the GPU
        // SSBOs before dispatch so the solver does not run on stale data.
        if (buf->hosts.registered) {
            if (!buf->uploadFromHosts(*vk_)) {
                return PFSF_ERROR_VULKAN;
            }
        }

        // Check tick budget — any island that does not reach the dispatch
        // block below keeps its previous-tick fail_buf contents, so we
        // MUST NOT drain those later. The break here, combined with the
        // per-island drain inside the dispatch block, is what closes the
        // "stale-failure leak" (review thread on pfsf_engine.cpp:429).
        auto elapsed = std::chrono::steady_clock::now() - t0;
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
        if (ms >= config_.tick_budget_ms) break;

        // Dispatch pipeline (mirrors PFSFDispatcher on the Java side).
        // Upload, convergence check, and submit/readback live outside this
        // loop in the async compute layer — tracked as the next M2c batch.
        bool dispatched = false;
        if (dispatcher_ && jacobi_->isReady() && descPool_ != VK_NULL_HANDLE) {
            VkCommandBuffer cmd = vk_->allocCmdBuffer();
            if (cmd != VK_NULL_HANDLE) {
                // Clear macro_residual before this tick's failure_scan so
                // atomicMax accumulates a per-tick value (parity with
                // PFSFDispatcher.clearMacroBlockResiduals).
                buf->recordClearMacroResiduals(cmd);

                dispatcher_->recordSolveSteps(cmd, *buf, STEPS_MINOR, descPool_);
                dispatcher_->recordPhaseFieldEvolve(cmd, *buf, descPool_);
                dispatcher_->recordFailureDetection(cmd, *buf, descPool_);
                vk_->submitAndWait(cmd);
                dispatched = true;

                // Capy: Recover descriptor pool allocations per-island tick.
                // Safe because submitAndWait guarantees the queue is idle.
                vkResetDescriptorPool(vk_->device(), descPool_, 0);

                // Post-tick macro-residual max readback — the stall ratio
                // heuristic in Dispatcher::recordSolveSteps consumes
                // {prev_max_macro_residual, last_max_macro_residual} on
                // the NEXT tick to decide whether to jump from RBGS to PCG.
                const float newMax = buf->readbackMacroResidualMax(*vk_);
                buf->prev_max_macro_residual = buf->last_max_macro_residual;
                buf->last_max_macro_residual = newMax;
            }
        }

        // Drain failures ONLY for islands that actually re-ran failure_scan
        // this tick. Islands whose dispatch was skipped (budget, pipeline
        // not ready, cmd alloc failure) keep stale fail_buf content from
        // the previous tick — draining them would re-fire the same events.
        if (drain_failures && dispatched && buf->allocated) {
            buf->readbackFailures(*vk_, failure_addr, failure_bytes);
        }

        // Auto-drain phi into the caller-registered stress DBB (if any)
        // AND mark the island clean — both gated on a successful
        // dispatch. When command recording was skipped (missing
        // dispatcher, descriptor pool unavailable, cmd alloc failure,
        // non-ready pipeline) phi still holds the previous tick's
        // solution; writing it back would publish stale data as if
        // this tick had solved, and clearing dirty would prevent the
        // island from being retried next tick. Preserve dirty + skip
        // readback so Java sees the stall instead of a silent stale
        // result.
        if (dispatched) {
            if (buf->hosts.stress_out && buf->hosts.stress_bytes > 0) {
                int32_t cap = static_cast<int32_t>(
                    buf->hosts.stress_bytes / sizeof(float));
                int32_t wrote = 0;
                buf->readbackPhi(*vk_,
                                 static_cast<float*>(buf->hosts.stress_out),
                                 cap, &wrote);
            }
            buf->markClean();
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(statsMtx_);
        lastTickMs_ = std::chrono::duration<float, std::milli>(t1 - t0).count();
    }

    return PFSF_OK;
}

int32_t PFSFEngine::drainCallbacks(int32_t* /*outEvents*/, int32_t /*capacity*/) {
    return 0;
}

// ═══ Stress readback ═══

pfsf_result PFSFEngine::readStress(int32_t island_id, float* out,
                                    int32_t cap, int32_t* count) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!out || !count) return PFSF_ERROR_INVALID_ARG;

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf) return PFSF_ERROR_INVALID_ARG;

    // The "stress" export is the raw phi scalar — the Java side derives the
    // utilisation ratio (phi / maxPhi) on top of it because that calculation
    // is already vectorised in PFSFIslandBuffer.readbackStress. Keeping the
    // native readback as raw phi avoids a second dispatch just to divide.
    if (!buf->readbackPhi(*vk_, out, cap, count)) {
        return PFSF_ERROR_VULKAN;
    }
    return PFSF_OK;
}

} // namespace pfsf
