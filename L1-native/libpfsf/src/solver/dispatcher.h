/**
 * @file dispatcher.h
 * @brief Solver sequencing orchestrator — mirrors the Java PFSFDispatcher.
 *
 * Composes RBGS/V-Cycle smoothing, phase-field evolve, and failure-scan
 * into one tick. Sequence and cadence match
 * Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDispatcher.java
 * so C++ / Java parity tests can hit the same dispatch order byte-for-byte.
 *
 * PCG Phase-2 is behind {@code supportsPCG()} — it activates once
 * IslandBuffer owns r/z/p/Ap/partialSums SSBOs (tracked as M2c-follow-up).
 * Until then the dispatcher falls back to pure RBGS + V-Cycle, which is
 * what the Java path uses when {@code BRConfig.isPFSFPCGEnabled()} is off.
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>

namespace pfsf {

class VulkanContext;
struct IslandBuffer;
class JacobiSolver;
class VCycleSolver;
class PhaseFieldSolver;
class FailureScan;
class PCGSolver;
class SparseScatterSolver;

class Dispatcher {
public:
    /** All solvers must outlive the dispatcher; references are non-owning. */
    Dispatcher(VulkanContext& vk,
               JacobiSolver&        rbgs,
               VCycleSolver&        vcycle,
               PhaseFieldSolver&    phaseField,
               FailureScan&         failure,
               PCGSolver&           pcg,
               SparseScatterSolver& sparse);

    /**
     * Record {@code steps} solve iterations — RBGS (+ V-Cycle every
     * MG_INTERVAL) with optional PCG tail once PCG state is allocated.
     *
     * Returns the number of iterations actually recorded.
     */
    int recordSolveSteps(VkCommandBuffer cmd, IslandBuffer& buf,
                         int steps, VkDescriptorPool pool);

    /**
     * Phase-field evolve — writes dField, reads hField. Skipped silently
     * when the island was allocated without phase-field buffers.
     */
    void recordPhaseFieldEvolve(VkCommandBuffer cmd, IslandBuffer& buf,
                                VkDescriptorPool pool);

    /**
     * Failure detection — one-pass scan. Java also triggers a compact
     * readback + phi reduce; those live in FailureRecorder (M2c+).
     */
    void recordFailureDetection(VkCommandBuffer cmd, IslandBuffer& buf,
                                VkDescriptorPool pool);

    /**
     * Sparse voxel-update scatter. Assumes the caller has already packed
     * @p updateCount records (≤ IslandBuffer::MAX_SPARSE_UPDATES_PER_TICK)
     * into @c buf.sparse_upload_mapped and issues a barrier between this
     * dispatch and the next solver step so the writes to
     * source/cond/type/maxPhi/rcomp/rtens are visible.
     *
     * Returns true if a dispatch was recorded, false if the pipeline
     * isn't ready, the island lacks the upload buffer, or @p updateCount
     * is zero.
     */
    bool recordSparseScatter(VkCommandBuffer cmd, IslandBuffer& buf,
                             VkDescriptorPool pool, int updateCount);

    /**
     * Runtime toggle for the PCG tail. Mirrors Java
     * {@code BRConfig.isPFSFPCGEnabled()}. When false the dispatcher
     * behaves as if PCG pipelines were never ready — pure RBGS +
     * V-Cycle only (Capy-ai R4, PR#187). Default: true.
     */
    void setPCGEnabled(bool enabled) { pcg_enabled_ = enabled; }
    bool pcgEnabled() const { return pcg_enabled_; }

private:
    /** PCG tail is a no-op until IslandBuffer gains r/z/p/Ap buffers. */
    bool supportsPCG(const IslandBuffer& buf) const;

    /** 4-dispatch PCG iteration body — defined in dispatcher_pcg.cpp. */
    void recordPCGStep(VkCommandBuffer cmd, IslandBuffer& buf,
                        VkDescriptorPool pool);

    /** One-time per-tick r=source-A*phi initialiser (also writes p, partials).*/
    void recordPCGInitialResidual(VkCommandBuffer cmd, IslandBuffer& buf,
                                   VkDescriptorPool pool);

    /** V-Cycle sweep: pre-smooth + restrict + coarse RBGS ×4 + prolong +
     *  post-smooth. Defined in dispatcher_vcycle.cpp.
     *  Returns the number of fine-grid smoothing iterations recorded
     *  (2 = 1 pre + 1 post) for dispatcher accounting. */
    int recordVCycle(VkCommandBuffer cmd, IslandBuffer& buf,
                     VkDescriptorPool pool);

    VulkanContext&       vk_;
    JacobiSolver&        rbgs_;
    VCycleSolver&        vcycle_;
    PhaseFieldSolver&    phaseField_;
    FailureScan&         failure_;
    PCGSolver&           pcg_;
    SparseScatterSolver& sparse_;

    // Default true = Java default (BRConfig.pfsfPCGEnabled = true). Java
    // overrides via pfsf_set_pcg_enabled after pfsf_init.
    bool                 pcg_enabled_ = true;
};

} // namespace pfsf
