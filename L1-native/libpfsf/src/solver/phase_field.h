/**
 * @file phase_field.h
 * @brief Phase-field fracture evolution — Ambati 2015 hybrid model.
 *        Mirrors Java PFSFPhaseFieldRecorder and
 *        assets/blockreality/shaders/compute/pfsf/phase_field_evolve.comp.glsl.
 *
 * CLAUDE.md invariant: hField is WRITE-OWNED by the RBGS/Jacobi smoothers.
 * This kernel reads hField only — never writes — to avoid GPU-side races.
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

/** Matches the GLSL PushConstants in phase_field_evolve.comp.glsl. */
struct PhaseFieldPushConstants {
    std::uint32_t Lx, Ly, Lz;
    float         l0;
    float         gcBase;
    float         relax;
    std::uint32_t spectralSplitEnabled;
};
static_assert(sizeof(PhaseFieldPushConstants) == 28,
              "PhaseFieldPushConstants must be 28 bytes to match phase_field_evolve.comp.glsl");

class PhaseFieldSolver {
public:
    explicit PhaseFieldSolver(VulkanContext& vk);
    ~PhaseFieldSolver();

    bool createPipeline();
    void destroyPipeline();

    bool isReady() const { return pipeline_.pipeline != VK_NULL_HANDLE; }

    /**
     * Record one phase-field evolution step.
     *
     * @param l0     regularisation length (≥ 2 blocks).
     * @param gcBase critical energy release rate (J/m²).
     * @param relax  relaxation factor ∈ (0,1]; 0.3 is the default.
     * @param spectralSplit 0 = legacy, 1 = AT2 + spectral split.
     */
    void recordEvolve(VkCommandBuffer cmdBuf, IslandBuffer& buf,
                      VkDescriptorPool pool,
                      float l0, float gcBase, float relax,
                      bool spectralSplit);

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  pipeline_{};
};

} // namespace pfsf
