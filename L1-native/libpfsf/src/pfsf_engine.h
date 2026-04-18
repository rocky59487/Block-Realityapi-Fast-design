/**
 * @file pfsf_engine.h
 * @brief Internal engine class — orchestrates Vulkan context, buffers, solvers.
 *
 * This is the C++ implementation behind the opaque pfsf_engine handle.
 */
#pragma once

#include <pfsf/pfsf_types.h>
#include <pfsf/pfsf.h>
#include "core/vulkan_context.h"
#include "core/buffer_manager.h"
#include "solver/jacobi_solver.h"
#include "solver/vcycle_solver.h"
#include "solver/phase_field.h"
#include "solver/failure_scan.h"
#include "solver/pcg_solver.h"
#include "solver/sparse_scatter.h"
#include "solver/dispatcher.h"

#include <memory>
#include <mutex>

namespace pfsf {

class PFSFEngine {
public:
    explicit PFSFEngine(const pfsf_config& config);
    ~PFSFEngine();

    PFSFEngine(const PFSFEngine&) = delete;
    PFSFEngine& operator=(const PFSFEngine&) = delete;

    // ── Lifecycle ──
    pfsf_result init();
    void        shutdown();
    bool        isAvailable() const { return available_; }

    // ── Stats (thread-safe) ──
    pfsf_result getStats(pfsf_stats* out) const;

    // ── Configuration ──
    void setMaterialLookup(pfsf_material_fn fn, void* ud);
    void setAnchorLookup(pfsf_anchor_fn fn, void* ud);
    void setFillRatioLookup(pfsf_fill_ratio_fn fn, void* ud);
    void setCuringLookup(pfsf_curing_fn fn, void* ud);
    void setWind(const pfsf_vec3* wind);

    // ── Island management ──
    pfsf_result addIsland(const pfsf_island_desc* desc);
    void        removeIsland(int32_t island_id);

    // ── Sparse notification ──
    pfsf_result notifyBlockChange(int32_t island_id, const pfsf_voxel_update* update);
    void        markFullRebuild(int32_t island_id);

    // ── DirectByteBuffer zero-copy registration (v0.3c) ──
    pfsf_result registerIslandBuffers(int32_t island_id,
                                       const pfsf_island_buffers* bufs);
    pfsf_result registerIslandLookups(int32_t island_id,
                                       const pfsf_island_lookups* lookups);
    pfsf_result registerStressReadback(int32_t island_id, void* addr, int64_t bytes);

    // ── Sparse scatter (v0.3c M2m) ──
    // Expose the island's VMA-mapped sparse upload buffer to the caller
    // and dispatch the scatter pipeline on demand.
    pfsf_result getSparseUploadBuffer(int32_t island_id,
                                       void** outAddr,
                                       int64_t* outBytes);
    pfsf_result notifySparseUpdates(int32_t island_id, int32_t updateCount);

    // ── Tick ──
    pfsf_result tick(const int32_t* dirty_ids, int32_t dirty_count,
                     int64_t epoch, pfsf_tick_result* result);

    /// DBB variant: same control-flow as tick(), but instead of writing
    /// failure events into a caller-owned pfsf_failure_event array, it
    /// serialises them into @p failure_addr using the wire format
    /// {count:int32}{x,y,z,type}×count expected by NativePFSFBridge.
    pfsf_result tickDbb(const int32_t* dirty_ids, int32_t dirty_count,
                        int64_t epoch, void* failure_addr, int64_t failure_bytes);

    // ── Stress readback ──
    pfsf_result readStress(int32_t island_id, float* out, int32_t cap, int32_t* count);

private:
    pfsf_config config_;
    bool        available_ = false;

    // ── Vulkan ──
    std::unique_ptr<VulkanContext>   vk_;
    std::unique_ptr<BufferManager>   buffers_;
    VkDescriptorPool                 descPool_ = VK_NULL_HANDLE;

    // ── Solvers ──
    std::unique_ptr<JacobiSolver>     jacobi_;
    std::unique_ptr<VCycleSolver>     vcycle_;
    std::unique_ptr<PhaseFieldSolver> phaseField_;
    std::unique_ptr<FailureScan>         failure_;
    std::unique_ptr<PCGSolver>           pcg_;
    std::unique_ptr<SparseScatterSolver> sparse_;
    std::unique_ptr<Dispatcher>          dispatcher_;

    // ── Callbacks ──
    pfsf_material_fn   materialFn_   = nullptr;  void* materialUD_   = nullptr;
    pfsf_anchor_fn     anchorFn_     = nullptr;  void* anchorUD_     = nullptr;
    pfsf_fill_ratio_fn fillRatioFn_  = nullptr;  void* fillRatioUD_  = nullptr;
    pfsf_curing_fn     curingFn_     = nullptr;  void* curingUD_     = nullptr;
    pfsf_vec3          wind_{0, 0, 0};

    // ── Stats lock ──
    mutable std::mutex statsMtx_;
    float              lastTickMs_ = 0.0f;
};

} // namespace pfsf
