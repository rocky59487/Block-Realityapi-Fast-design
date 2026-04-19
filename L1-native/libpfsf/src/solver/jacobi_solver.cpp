/**
 * @file jacobi_solver.cpp
 * @brief RBGS Jacobi solver — Phase 1 skeleton.
 *
 * Phase 3 TODO:
 *   - Embed SPIR-V for rbgs.comp shader
 *   - Create descriptor set layout (phi, phi_prev, source, conductivity, type)
 *   - Push constants: Lx, Ly, Lz, omega, color
 *   - Dispatch 8 colors × ceil(N/256) workgroups
 */
#include "jacobi_solver.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "core/constants.h"
#include <cstdio>

namespace pfsf {

JacobiSolver::JacobiSolver(VulkanContext& vk) : vk_(vk) {}

JacobiSolver::~JacobiSolver() {
    destroyPipeline();
}

bool JacobiSolver::createPipeline() {
    // Phase 3: compile rbgs.comp GLSL → SPIR-V, create pipeline
    fprintf(stderr, "[libpfsf] JacobiSolver::createPipeline — stub (Phase 3)\n");
    return true;
}

void JacobiSolver::destroyPipeline() {
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

void JacobiSolver::recordStep(VkCommandBuffer /*cmdBuf*/, IslandBuffer& buf,
                               VkDescriptorPool /*pool*/) {
    // Phase 3: full implementation
    //
    // Pseudocode (mirrors Java PFSFVCycleRecorder.recordRBGSStep):
    //   for color in 0..7:
    //     bind pipeline
    //     allocate + bind descriptor set (phi, phi_prev, source, cond, type)
    //     push constants {Lx, Ly, Lz, omega, color}
    //     dispatch ceil(N / WG_RBGS) workgroups
    //     memory barrier
    //   swap phi_buf ↔ phi_prev_buf (flip)

    buf.chebyshev_iter++;
    (void)buf;  // suppress unused warning in stub
}

} // namespace pfsf
