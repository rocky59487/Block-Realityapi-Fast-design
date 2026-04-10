/**
 * @file pfsf_engine.cpp
 * @brief PFSF engine implementation — lifecycle, tick, callbacks.
 */
#include "pfsf_engine.h"
#include "core/constants.h"
#include <chrono>
#include <cstdio>
#include <algorithm>

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

    if (!jacobi_->createPipeline() ||
        !vcycle_->createPipeline() ||
        !phaseField_->createPipeline()) {
        shutdown();
        return PFSF_ERROR_VULKAN;
    }

    available_ = true;
    fprintf(stderr, "[libpfsf] Engine initialized (%s, VRAM: %lld MB)\n",
            vk_->deviceName().c_str(),
            (long long)(vk_->deviceLocalBytes() / (1024 * 1024)));
    return PFSF_OK;
}

void PFSFEngine::shutdown() {
    if (!vk_) return;

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

    // ★ Use int64_t to prevent signed integer overflow UB
    int64_t n64 = static_cast<int64_t>(desc->lx) * desc->ly * desc->lz;
    if (n64 < 1 || n64 > config_.max_island_size) return PFSF_ERROR_ISLAND_FULL;

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

// ═══ Tick ═══

pfsf_result PFSFEngine::tick(const int32_t* dirty_ids, int32_t dirty_count,
                              int64_t /*epoch*/, pfsf_tick_result* result) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (dirty_count > 0 && !dirty_ids) return PFSF_ERROR_INVALID_ARG;

    auto t0 = std::chrono::steady_clock::now();

    if (result) result->count = 0;

    for (int32_t i = 0; i < dirty_count; i++) {
        int32_t id = dirty_ids[i];
        IslandBuffer* buf = buffers_->get(id);
        if (!buf || !buf->dirty) continue;

        // Check tick budget
        auto elapsed = std::chrono::steady_clock::now() - t0;
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
        if (ms >= config_.tick_budget_ms) break;

        // Phase 3: actual GPU dispatch pipeline
        //   1. Sparse update or full rebuild
        //   2. Convergence check (maxPhiPrev / maxPhiPrevPrev)
        //   3. RBGS iterations (jacobi_->recordStep)
        //   4. V-Cycle at MG_INTERVAL (vcycle_->recordVCycle)
        //   5. Phase-field evolution (phaseField_->recordEvolve)
        //   6. Failure scan + compact readback
        //   7. Submit + read results

        buf->markClean();
    }

    auto t1 = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(statsMtx_);
        lastTickMs_ = std::chrono::duration<float, std::milli>(t1 - t0).count();
    }

    return PFSF_OK;
}

// ═══ Stress readback ═══

pfsf_result PFSFEngine::readStress(int32_t island_id, float* out,
                                    int32_t cap, int32_t* count) {
    if (!available_) return PFSF_ERROR_NOT_INIT;
    if (!out || !count) return PFSF_ERROR_INVALID_ARG;

    IslandBuffer* buf = buffers_ ? buffers_->get(island_id) : nullptr;
    if (!buf) return PFSF_ERROR_INVALID_ARG;

    int32_t n = static_cast<int32_t>(std::min(buf->N(), static_cast<int64_t>(cap)));
    // Phase 3: GPU → staging → CPU readback of phi/maxPhi ratio
    // For now, zero-fill
    std::fill(out, out + n, 0.0f);
    *count = n;
    return PFSF_OK;
}

} // namespace pfsf
