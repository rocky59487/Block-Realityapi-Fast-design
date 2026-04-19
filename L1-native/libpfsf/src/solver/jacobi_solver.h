/**
 * @file jacobi_solver.h
 * @brief Red-Black Gauss-Seidel (RBGS) Jacobi solver — GPU compute dispatch.
 *
 * Mirrors Java PFSFVCycleRecorder.recordRBGSStep().
 * Phase 3: replace stubs with actual Vulkan pipeline dispatch.
 */
#pragma once

#include <vulkan/vulkan.h>

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

class JacobiSolver {
public:
    explicit JacobiSolver(VulkanContext& vk);
    ~JacobiSolver();

    /** Create compute pipeline + descriptor set layout. */
    bool createPipeline();

    /** Destroy pipeline resources. */
    void destroyPipeline();

    /**
     * Record one RBGS iteration step into cmdBuf.
     *
     * Phase 3 TODO: bind descriptors, push constants (Lx,Ly,Lz,omega),
     *               dispatch ceil(N / WG_RBGS) workgroups per color.
     */
    void recordStep(VkCommandBuffer cmdBuf, IslandBuffer& buf,
                    VkDescriptorPool pool);

private:
    VulkanContext&         vk_;
    VkPipeline             pipeline_       = VK_NULL_HANDLE;
    VkPipelineLayout       pipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout  dsLayout_       = VK_NULL_HANDLE;
};

} // namespace pfsf
