/**
 * @file jacobi_solver.h
 * @brief Red-Black Gauss-Seidel (RBGS) 8-colour in-place smoother — GPU
 *        compute dispatch. Mirrors the Java PFSFDispatcher inner RBGS loop
 *        and the GLSL shader at
 *        assets/blockreality/shaders/compute/pfsf/rbgs_smooth.comp.glsl.
 *
 * The 26-connectivity stencil and the edge/corner shear penalties live on
 * the GLSL side — CLAUDE.md calls those out as cross-shader invariants,
 * so we pull the compiled SPIR-V from the shared br_core SpirvRegistry
 * instead of duplicating any of that math here.
 *
 * Push-constant layout (matches rbgs_smooth.comp.glsl):
 *   uint32 Lx, Ly, Lz
 *   uint32 colorPass    (0..7)
 *   float  damping
 */
#pragma once

#include <vulkan/vulkan.h>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

class JacobiSolver {
public:
    explicit JacobiSolver(VulkanContext& vk);
    ~JacobiSolver();

    /** Build the compute pipeline from the cached SPIR-V blob. */
    bool createPipeline();

    /** Destroy pipeline resources. */
    void destroyPipeline();

    /**
     * Record one RBGS sweep (all 8 colour passes) into @p cmdBuf.
     *
     * @param cmdBuf   caller-managed compute command buffer (must be in
     *                 recording state).
     * @param buf      island under solve (source of VkBuffer handles).
     * @param pool     descriptor pool that can allocate 1 set from
     *                 {@link br_core::ComputePipeline::set_layout}. Callers
     *                 should route this through br_core's descriptor cache
     *                 so allocations are amortised (LRU > 98 % hit rate).
     * @param damping  0.0 = no damping, 0.995 = Chebyshev warm-up damping.
     */
    void recordStep(VkCommandBuffer cmdBuf, IslandBuffer& buf,
                    VkDescriptorPool pool, float damping);

    bool isReady() const { return pipeline_.pipeline != VK_NULL_HANDLE; }

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  pipeline_{};
};

/** Must match the GLSL PushConstants block byte-for-byte. */
struct RBGSPushConstants {
    std::uint32_t Lx;
    std::uint32_t Ly;
    std::uint32_t Lz;
    std::uint32_t colorPass;
    float         damping;
};
static_assert(sizeof(RBGSPushConstants) == 20,
              "RBGSPushConstants must be 20 bytes to match rbgs_smooth.comp.glsl");

} // namespace pfsf
