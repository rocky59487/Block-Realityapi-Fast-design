/**
 * @file vcycle_solver.h
 * @brief V-Cycle multigrid — restriction + prolongation pipelines.
 *        Mirrors the Java PFSFVCycleRecorder and the GLSL sibling shaders.
 *        Coarse-grid RBGS solves reuse the fine-grid JacobiSolver pipeline
 *        (same binding shape, different descriptor write targets).
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

/** Push constants for both mg_restrict and mg_prolong — identical layout. */
struct MGPushConstants {
    std::uint32_t Lx_fine,   Ly_fine,   Lz_fine;
    std::uint32_t Lx_coarse, Ly_coarse, Lz_coarse;
};
static_assert(sizeof(MGPushConstants) == 24,
              "MGPushConstants must be 24 bytes to match mg_restrict/prolong.comp.glsl");

/** Push constants for the coarse-grid Jacobi smoother (jacobi_smooth.comp).
 *  Mirrors the Java PFSFVCycleRecorder.dispatchRBGSPass push block — the
 *  shader only declares the first 28 bytes but Java always pushes 32 to
 *  keep a spare redBlackPass slot; we match that layout for parity. */
struct CoarseRBGSPushConstants {
    std::uint32_t Lx, Ly, Lz;
    float         omega;
    float         rho_spec;
    std::uint32_t iter;
    float         damping;
    std::uint32_t redBlackPass;
};
static_assert(sizeof(CoarseRBGSPushConstants) == 32,
              "CoarseRBGSPushConstants must be 32 bytes to match Java dispatchRBGSPass");

class VCycleSolver {
public:
    explicit VCycleSolver(VulkanContext& vk);
    ~VCycleSolver();

    bool createPipeline();
    void destroyPipeline();

    bool isReady() const {
        return restrict_.pipeline     != VK_NULL_HANDLE
            && prolong_.pipeline      != VK_NULL_HANDLE
            && coarse_rbgs_.pipeline  != VK_NULL_HANDLE;
    }

    VkPipeline        restrictPipeline()       const { return restrict_.pipeline;        }
    VkPipelineLayout  restrictPipelineLayout() const { return restrict_.pipeline_layout; }
    VkDescriptorSetLayout restrictLayout()     const { return restrict_.set_layout;      }

    VkPipeline        prolongPipeline()        const { return prolong_.pipeline;         }
    VkPipelineLayout  prolongPipelineLayout()  const { return prolong_.pipeline_layout;  }
    VkDescriptorSetLayout prolongLayout()      const { return prolong_.set_layout;       }

    VkPipeline        coarseRBGSPipeline()       const { return coarse_rbgs_.pipeline;        }
    VkPipelineLayout  coarseRBGSPipelineLayout() const { return coarse_rbgs_.pipeline_layout; }
    VkDescriptorSetLayout coarseRBGSLayout()     const { return coarse_rbgs_.set_layout;      }

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  restrict_{};
    br_core::ComputePipeline  prolong_{};
    br_core::ComputePipeline  coarse_rbgs_{};
};

} // namespace pfsf
