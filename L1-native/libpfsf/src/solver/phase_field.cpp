/**
 * @file phase_field.cpp
 * @brief Phase-field fracture solver — Phase 1 skeleton.
 *
 * Phase 3 TODO:
 *   - Embed SPIR-V for phase_field_evolve.comp
 *   - Descriptor set: phi, hField, dField, cond, type, failFlags, hydration
 *   - Push constants: Lx, Ly, Lz, l0 (1.5), gc_base (material), relax (0.3)
 *   - d > 0.95 → write FAIL_TENSION to failFlags
 */
#include "phase_field.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "core/constants.h"
#include <cstdio>

namespace pfsf {

PhaseFieldSolver::PhaseFieldSolver(VulkanContext& vk) : vk_(vk) {}

PhaseFieldSolver::~PhaseFieldSolver() {
    destroyPipeline();
}

bool PhaseFieldSolver::createPipeline() {
    fprintf(stderr, "[libpfsf] PhaseFieldSolver::createPipeline — stub (Phase 3)\n");
    return true;
}

void PhaseFieldSolver::destroyPipeline() {
    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    if (pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(dev, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
    }
    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(dev, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
    }
    if (dsLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(dev, dsLayout_, nullptr);
        dsLayout_ = VK_NULL_HANDLE;
    }
}

void PhaseFieldSolver::recordEvolve(VkCommandBuffer /*cmdBuf*/, IslandBuffer& /*buf*/,
                                     VkDescriptorPool /*pool*/) {
    // Phase 3: full implementation
    //
    // Pseudocode (mirrors Java PFSFEngine.recordPhaseFieldEvolve):
    //   bind phaseFieldPipeline
    //   allocate descriptor set with 7 bindings:
    //     0: phi (readonly)
    //     1: hField (readwrite — max strain energy, irreversible)
    //     2: dField (readwrite — damage ∈ [0,1])
    //     3: conductivity (readonly, SoA)
    //     4: type (readonly)
    //     5: failFlags (write — d > 0.95 triggers failure)
    //     6: hydration (readonly — ICuringManager degree)
    //   push constants: {Lx, Ly, Lz, l0=1.5, gc_base=100.0, relax=0.3}
    //   dispatch ceil(N / WG_SCAN) workgroups
    //   compute memory barrier
}

} // namespace pfsf
