/**
 * @file compute_pipeline.h
 * @brief Shared helper for building Vulkan compute pipelines from a
 *        SpirvRegistry lookup + descriptor layout + push-constant struct.
 *
 * Every solver in v0.3c (libpfsf RBGS/PCG/MG/failure/phase_field,
 * libfluid Jacobi/advection, librender dispatchers) creates pipelines
 * the same way:
 *
 *   1. Look up the SPIR-V blob by canonical name.
 *   2. Build a {@link VkShaderModule} from the words.
 *   3. Build a {@link VkDescriptorSetLayout} from a fixed binding table.
 *   4. Build a {@link VkPipelineLayout} with an optional push-constant range.
 *   5. Build a {@link VkComputePipelineCreateInfo} and submit it through
 *      the shared {@link PipelineCache}.
 *
 * This helper collapses that ~60-line boilerplate into one call so domain
 * code stays focused on dispatch logic.
 */
#ifndef BR_CORE_COMPUTE_PIPELINE_H
#define BR_CORE_COMPUTE_PIPELINE_H

#include <vulkan/vulkan.h>
#include <cstdint>
#include <string_view>
#include <vector>

namespace br_core {

/**
 * Descriptor binding entry for the auto-built set layout. `binding` matches
 * the GLSL `layout(binding = N)` value; `type` is almost always
 * {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} for PFSF/Fluid.
 */
struct PipelineLayoutBinding {
    std::uint32_t        binding;
    VkDescriptorType     type;
    std::uint32_t        count = 1;   // array size; 1 for plain SSBO
    VkShaderStageFlags   stages = VK_SHADER_STAGE_COMPUTE_BIT;
};

/** Push-constant range description (compute shader only; stages are fixed). */
struct PushConstantRange {
    std::uint32_t offset = 0;
    std::uint32_t size   = 0;   // 0 = no push constants
};

/**
 * Output of {@link build_compute_pipeline}. Callers own all handles and
 * must destroy them when the solver tears down. {@code set_layout} may
 * be shared across multiple pipelines that use the same binding shape ??
 * domain code is free to cache it.
 */
struct ComputePipeline {
    VkPipeline            pipeline      = VK_NULL_HANDLE;
    VkPipelineLayout      pipeline_layout = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout    = VK_NULL_HANDLE;
};

/**
 * Build a compute pipeline from a SPIR-V registry entry. Returns a zero-
 * initialised struct on any failure (lookup miss, VkResult != VK_SUCCESS,
 * core singleton unavailable). Caller must check {@code pipeline != 0}.
 *
 * @param canonical_name   registry key, e.g. "compute/pfsf/rbgs_smooth.comp"
 * @param bindings         descriptor set binding table
 * @param push             push-constant range (size = 0 means none)
 * @param entry_point      shader entry point (default "main")
 */
ComputePipeline build_compute_pipeline(
        std::string_view canonical_name,
        const std::vector<PipelineLayoutBinding>& bindings,
        PushConstantRange push = {},
        const char* entry_point = "main");

/** Destroys any non-null handles. Safe to call on a zero-initialised struct. */
void destroy_compute_pipeline(ComputePipeline& p);

} // namespace br_core

#endif // BR_CORE_COMPUTE_PIPELINE_H
