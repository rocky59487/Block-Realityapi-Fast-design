/**
 * @file pcg_solver.h
 * @brief PCG (Jacobi-preconditioned Conjugate Gradient) — 4-pipeline
 *        dispatch module. Mirrors the Java PFSFPCGRecorder and the GLSL
 *        sibling shaders under assets/blockreality/shaders/compute/pfsf/.
 *
 * Four compute pipelines, all sharing the 26-connectivity stencil and
 * Jacobi preconditioner z = r/diag(A₂₆):
 *
 *   pcg_matvec     — Ap = A₂₆·p
 *   pcg_update     — alpha step + Jacobi precondition + r·z reduction
 *   pcg_direction  — p = z + beta·p (with recomputed z)
 *   pcg_dot        — two-pass dot product (used for pAp, r·r etc.)
 *
 * Dispatch order per iteration (mirrors PFSFDispatcher):
 *   1. matvec(p → Ap)
 *   2. dot(p, Ap)                 → pAp
 *   3. update(init=0, alpha)      → writes phi/r/z + partialSums
 *   4. dot(partialSums)           → rTz_new
 *   5. direction(r, p)            → p ← z + β·p
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

/** Matches the GLSL PushConstants in pcg_matvec.comp.glsl. */
struct PCGMatvecPushConstants {
    std::uint32_t Lx, Ly, Lz;
};
static_assert(sizeof(PCGMatvecPushConstants) == 12,
              "PCGMatvecPushConstants must be 12 bytes to match pcg_matvec.comp.glsl");

/** Matches the GLSL PushConstants in pcg_update.comp.glsl. */
struct PCGUpdatePushConstants {
    std::uint32_t Lx, Ly, Lz;
    float         alpha;
    std::uint32_t isInit;
    std::uint32_t padding;
};
static_assert(sizeof(PCGUpdatePushConstants) == 24,
              "PCGUpdatePushConstants must be 24 bytes to match pcg_update.comp.glsl");

/** Matches the GLSL PushConstants in pcg_direction.comp.glsl. */
struct PCGDirectionPushConstants {
    std::uint32_t Lx, Ly, Lz;
};
static_assert(sizeof(PCGDirectionPushConstants) == 12,
              "PCGDirectionPushConstants must be 12 bytes to match pcg_direction.comp.glsl");

/** Matches the GLSL PushConstants in pcg_dot.comp.glsl. */
struct PCGDotPushConstants {
    std::uint32_t N;
    std::uint32_t isPass2;
    std::uint32_t outputSlot;
    std::uint32_t padding;
};
static_assert(sizeof(PCGDotPushConstants) == 16,
              "PCGDotPushConstants must be 16 bytes to match pcg_dot.comp.glsl");

class PCGSolver {
public:
    explicit PCGSolver(VulkanContext& vk);
    ~PCGSolver();

    bool createPipelines();
    void destroyPipelines();

    bool isReady() const {
        return matvec_.pipeline      != VK_NULL_HANDLE
            && update_.pipeline      != VK_NULL_HANDLE
            && direction_.pipeline   != VK_NULL_HANDLE
            && dot_.pipeline         != VK_NULL_HANDLE;
    }

    /** Accessors for dispatcher-side recording — one descriptor set per
     *  stage. The dispatcher owns the sequencing; this class owns the
     *  pipelines and descriptor set layouts only. */
    VkDescriptorSetLayout matvecLayout()    const { return matvec_.set_layout;    }
    VkDescriptorSetLayout updateLayout()    const { return update_.set_layout;    }
    VkDescriptorSetLayout directionLayout() const { return direction_.set_layout; }
    VkDescriptorSetLayout dotLayout()       const { return dot_.set_layout;       }

    VkPipeline matvecPipeline()    const { return matvec_.pipeline;    }
    VkPipeline updatePipeline()    const { return update_.pipeline;    }
    VkPipeline directionPipeline() const { return direction_.pipeline; }
    VkPipeline dotPipeline()       const { return dot_.pipeline;       }

    VkPipelineLayout matvecPipelineLayout()    const { return matvec_.pipeline_layout;    }
    VkPipelineLayout updatePipelineLayout()    const { return update_.pipeline_layout;    }
    VkPipelineLayout directionPipelineLayout() const { return direction_.pipeline_layout; }
    VkPipelineLayout dotPipelineLayout()       const { return dot_.pipeline_layout;       }

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  matvec_{};
    br_core::ComputePipeline  update_{};
    br_core::ComputePipeline  direction_{};
    br_core::ComputePipeline  dot_{};
};

} // namespace pfsf
