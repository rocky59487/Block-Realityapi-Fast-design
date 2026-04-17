/**
 * @file failure_scan.h
 * @brief Failure scan kernel — detects cantilever/crush/tension-break and
 *        writes per-voxel failure codes + macro-block residual bits.
 *        Mirrors the Java PFSFFailureRecorder and
 *        assets/blockreality/shaders/compute/pfsf/failure_scan.comp.glsl.
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

/** Matches the GLSL PC in failure_scan.comp.glsl. */
struct FailureScanPushConstants {
    std::uint32_t Lx, Ly, Lz;
    float         phi_orphan;   // ~1e6 (maps to PFSFConstants.PHI_ORPHAN)
};
static_assert(sizeof(FailureScanPushConstants) == 16,
              "FailureScanPushConstants must be 16 bytes to match failure_scan.comp.glsl");

class FailureScan {
public:
    explicit FailureScan(VulkanContext& vk);
    ~FailureScan();

    bool createPipeline();
    void destroyPipeline();

    bool isReady() const { return pipeline_.pipeline != VK_NULL_HANDLE; }

    /**
     * Record a failure-scan dispatch covering the whole island.
     *
     * @param phi_orphan cantilever-orphan threshold (PFSFConstants.PHI_ORPHAN).
     */
    void recordStep(VkCommandBuffer cmd, IslandBuffer& buf,
                    VkDescriptorPool pool, float phi_orphan);

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  pipeline_{};
};

} // namespace pfsf
