/**
 * @file vcycle_solver.h
 * @brief V-Cycle multigrid solver — GPU compute dispatch.
 *
 * Mirrors Java PFSFVCycleRecorder.recordVCycle().
 * Phase 3: replace stubs with restriction → coarse solve → prolongation.
 */
#pragma once

#include <vulkan/vulkan.h>

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

class VCycleSolver {
public:
    explicit VCycleSolver(VulkanContext& vk);
    ~VCycleSolver();

    bool createPipeline();
    void destroyPipeline();

    /**
     * Record a full V-Cycle (restriction → coarse RBGS → prolongation)
     * into cmdBuf.
     *
     * Phase 3 TODO: implement 2-level multigrid with coarse grid buffers.
     */
    void recordVCycle(VkCommandBuffer cmdBuf, IslandBuffer& buf,
                      VkDescriptorPool pool);

private:
    VulkanContext& vk_;

    // Restriction pipeline (fine → coarse)
    VkPipeline             restrictPipeline_       = VK_NULL_HANDLE;
    VkPipelineLayout       restrictPipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout  restrictDSLayout_        = VK_NULL_HANDLE;

    // Prolongation pipeline (coarse → fine)
    VkPipeline             prolongPipeline_         = VK_NULL_HANDLE;
    VkPipelineLayout       prolongPipelineLayout_   = VK_NULL_HANDLE;
    VkDescriptorSetLayout  prolongDSLayout_          = VK_NULL_HANDLE;
};

} // namespace pfsf
