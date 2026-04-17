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

class Dispatcher {
public:
    /** All solvers must outlive the dispatcher; references are non-owning. */
    Dispatcher(VulkanContext& vk,
               JacobiSolver&      rbgs,
               VCycleSolver&      vcycle,
               PhaseFieldSolver&  phaseField,
               FailureScan&       failure,
               PCGSolver&         pcg);

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

private:
    /** PCG tail is a no-op until IslandBuffer gains r/z/p/Ap buffers. */
    bool supportsPCG(const IslandBuffer& buf) const;

    VulkanContext&     vk_;
    JacobiSolver&      rbgs_;
    VCycleSolver&      vcycle_;
    PhaseFieldSolver&  phaseField_;
    FailureScan&       failure_;
    PCGSolver&         pcg_;
};

} // namespace pfsf
