/**
 * @file phase_field.h
 * @brief Phase-field fracture evolution — GPU compute dispatch.
 *
 * Ambati 2015 hybrid phase-field model.
 * Mirrors Java PFSFEngine.recordPhaseFieldEvolve().
 */
#pragma once

#include <vulkan/vulkan.h>

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

class PhaseFieldSolver {
public:
    explicit PhaseFieldSolver(VulkanContext& vk);
    ~PhaseFieldSolver();

    bool createPipeline();
    void destroyPipeline();

    /**
     * Record phase-field evolution dispatch.
     *
     * Phase 3 TODO:
     *   - 7 bindings: phi, hField, dField, conductivity, type, failFlags, hydration
     *   - Push constants: Lx, Ly, Lz, l0, gcBase, relax (24 bytes)
     *   - Dispatch ceil(N / WG_SCAN) workgroups
     */
    void recordEvolve(VkCommandBuffer cmdBuf, IslandBuffer& buf,
                      VkDescriptorPool pool);

private:
    VulkanContext&         vk_;
    VkPipeline             pipeline_       = VK_NULL_HANDLE;
    VkPipelineLayout       pipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout  dsLayout_       = VK_NULL_HANDLE;
};

} // namespace pfsf
