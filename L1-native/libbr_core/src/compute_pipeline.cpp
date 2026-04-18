#include "br_core/compute_pipeline.h"
#include "br_core/br_core.h"

#include <cstdio>
#include <vector>

namespace br_core {

ComputePipeline build_compute_pipeline(
        std::string_view canonical_name,
        const std::vector<PipelineLayoutBinding>& bindings,
        PushConstantRange push,
        const char* entry_point) {

    ComputePipeline out{};

    Core* core = get_singleton();
    if (core == nullptr) {
        std::fprintf(stderr, "[br_core] build_compute_pipeline(%.*s): core singleton unavailable\n",
                     static_cast<int>(canonical_name.size()), canonical_name.data());
        return out;
    }
    VkDevice dev = core->device.device();
    if (dev == VK_NULL_HANDLE) return out;

    SpirvBlob blob = core->spirv.lookup(canonical_name);
    if (blob.words == nullptr || blob.word_count == 0) {
        std::fprintf(stderr, "[br_core] build_compute_pipeline(%.*s): SPIR-V blob missing\n",
                     static_cast<int>(canonical_name.size()), canonical_name.data());
        return out;
    }

    // 1. Shader module
    VkShaderModule module = VK_NULL_HANDLE;
    {
        VkShaderModuleCreateInfo smi{};
        smi.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smi.codeSize = static_cast<std::size_t>(blob.word_count) * sizeof(std::uint32_t);
        smi.pCode    = blob.words;
        if (vkCreateShaderModule(dev, &smi, nullptr, &module) != VK_SUCCESS) {
            std::fprintf(stderr, "[br_core] vkCreateShaderModule failed for %.*s\n",
                         static_cast<int>(canonical_name.size()), canonical_name.data());
            return out;
        }
    }

    // 2. Descriptor set layout
    std::vector<VkDescriptorSetLayoutBinding> vk_bindings;
    vk_bindings.reserve(bindings.size());
    for (const auto& b : bindings) {
        VkDescriptorSetLayoutBinding lb{};
        lb.binding         = b.binding;
        lb.descriptorType  = b.type;
        lb.descriptorCount = b.count;
        lb.stageFlags      = b.stages;
        vk_bindings.push_back(lb);
    }
    {
        VkDescriptorSetLayoutCreateInfo dsli{};
        dsli.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dsli.bindingCount = static_cast<std::uint32_t>(vk_bindings.size());
        dsli.pBindings    = vk_bindings.empty() ? nullptr : vk_bindings.data();
        if (vkCreateDescriptorSetLayout(dev, &dsli, nullptr, &out.set_layout) != VK_SUCCESS) {
            vkDestroyShaderModule(dev, module, nullptr);
            return out;
        }
    }

    // 3. Pipeline layout
    {
        VkPushConstantRange pcr{};
        pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pcr.offset     = push.offset;
        pcr.size       = push.size;

        VkPipelineLayoutCreateInfo pli{};
        pli.sType                  = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pli.setLayoutCount         = 1;
        pli.pSetLayouts            = &out.set_layout;
        pli.pushConstantRangeCount = (push.size > 0) ? 1 : 0;
        pli.pPushConstantRanges    = (push.size > 0) ? &pcr : nullptr;
        if (vkCreatePipelineLayout(dev, &pli, nullptr, &out.pipeline_layout) != VK_SUCCESS) {
            vkDestroyDescriptorSetLayout(dev, out.set_layout, nullptr);
            vkDestroyShaderModule(dev, module, nullptr);
            out.set_layout = VK_NULL_HANDLE;
            return out;
        }
    }

    // 4. Compute pipeline
    {
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage  = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = module;
        stage.pName  = entry_point;

        VkComputePipelineCreateInfo cpci{};
        cpci.sType  = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        cpci.stage  = stage;
        cpci.layout = out.pipeline_layout;

        VkPipelineCache cache = core->pipelines.handle();
        VkResult rc = vkCreateComputePipelines(dev, cache, 1, &cpci, nullptr, &out.pipeline);
        vkDestroyShaderModule(dev, module, nullptr);
        if (rc != VK_SUCCESS) {
            vkDestroyPipelineLayout(dev, out.pipeline_layout, nullptr);
            vkDestroyDescriptorSetLayout(dev, out.set_layout, nullptr);
            out.pipeline_layout = VK_NULL_HANDLE;
            out.set_layout      = VK_NULL_HANDLE;
            std::fprintf(stderr, "[br_core] vkCreateComputePipelines failed (rc=%d) for %.*s\n",
                         static_cast<int>(rc),
                         static_cast<int>(canonical_name.size()), canonical_name.data());
            return out;
        }
    }

    return out;
}

void destroy_compute_pipeline(ComputePipeline& p) {
    Core* core = get_singleton();
    if (core == nullptr) return;
    VkDevice dev = core->device.device();
    if (dev == VK_NULL_HANDLE) return;
    destroy_compute_pipeline(dev, p);
}

void destroy_compute_pipeline(VkDevice dev, ComputePipeline& p) {
    if (dev == VK_NULL_HANDLE) return;
    if (p.pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(dev, p.pipeline, nullptr);
        p.pipeline = VK_NULL_HANDLE;
    }
    if (p.pipeline_layout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(dev, p.pipeline_layout, nullptr);
        p.pipeline_layout = VK_NULL_HANDLE;
    }
    if (p.set_layout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(dev, p.set_layout, nullptr);
        p.set_layout = VK_NULL_HANDLE;
    }
}

ComputePipeline build_compute_pipeline(
        VkDevice dev,
        VkPipelineCache pipeline_cache,
        std::string_view canonical_name,
        const std::vector<PipelineLayoutBinding>& bindings,
        PushConstantRange push,
        const char* entry_point) {

    ComputePipeline out{};
    if (dev == VK_NULL_HANDLE) return out;

    // SPIR-V lookup — the device-explicit overload exists so callers that
    // own their own VkDevice (libpfsf's VulkanContext) can build pipelines
    // before br_core's singleton is brought up. We must NOT trigger
    // get_singleton() here — that would create a second VkInstance/VkDevice
    // as a side-effect, violating the single-device contract. So the
    // lookup cascades:
    //   1) If the Core singleton is already up, its registry already
    //      consumed the deferred queue during bring_up — look up there.
    //   2) If (1) misses, fall back to the static deferred queue. This
    //      catches blobs registered AFTER bring_up(): Core::bring_up()
    //      drains the deferred queue exactly once, but blockreality_fluid
    //      and blockreality_render are loaded independently from Java
    //      and force-link br_shaders, so their static initializers can
    //      register new blobs after br_core is already up. Without this
    //      fallback those late blobs would stay stranded in the deferred
    //      queue forever and any later build_compute_pipeline() for a
    //      fluid/render canonical shader name would fail with "blob
    //      missing".
    //   3) Otherwise (no singleton), read directly from the deferred queue.
    SpirvBlob blob{ nullptr, 0 };
    if (Core* core = peek_singleton()) {
        blob = core->spirv.lookup(canonical_name);
        if (blob.words == nullptr || blob.word_count == 0) {
            blob = SpirvRegistry::lookup_deferred(canonical_name);
        }
    } else {
        blob = SpirvRegistry::lookup_deferred(canonical_name);
    }
    if (blob.words == nullptr || blob.word_count == 0) {
        std::fprintf(stderr, "[br_core] build_compute_pipeline(%.*s): SPIR-V blob missing "
                     "(neither live registry nor deferred queue had the blob)\n",
                     static_cast<int>(canonical_name.size()), canonical_name.data());
        return out;
    }

    VkShaderModule module = VK_NULL_HANDLE;
    {
        VkShaderModuleCreateInfo smi{};
        smi.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smi.codeSize = static_cast<std::size_t>(blob.word_count) * sizeof(std::uint32_t);
        smi.pCode    = blob.words;
        if (vkCreateShaderModule(dev, &smi, nullptr, &module) != VK_SUCCESS) {
            std::fprintf(stderr, "[br_core] vkCreateShaderModule failed for %.*s\n",
                         static_cast<int>(canonical_name.size()), canonical_name.data());
            return out;
        }
    }

    std::vector<VkDescriptorSetLayoutBinding> vk_bindings;
    vk_bindings.reserve(bindings.size());
    for (const auto& b : bindings) {
        VkDescriptorSetLayoutBinding lb{};
        lb.binding         = b.binding;
        lb.descriptorType  = b.type;
        lb.descriptorCount = b.count;
        lb.stageFlags      = b.stages;
        vk_bindings.push_back(lb);
    }
    {
        VkDescriptorSetLayoutCreateInfo dsli{};
        dsli.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dsli.bindingCount = static_cast<std::uint32_t>(vk_bindings.size());
        dsli.pBindings    = vk_bindings.empty() ? nullptr : vk_bindings.data();
        if (vkCreateDescriptorSetLayout(dev, &dsli, nullptr, &out.set_layout) != VK_SUCCESS) {
            vkDestroyShaderModule(dev, module, nullptr);
            return out;
        }
    }

    {
        VkPushConstantRange pcr{};
        pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pcr.offset     = push.offset;
        pcr.size       = push.size;

        VkPipelineLayoutCreateInfo pli{};
        pli.sType                  = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pli.setLayoutCount         = 1;
        pli.pSetLayouts            = &out.set_layout;
        pli.pushConstantRangeCount = (push.size > 0) ? 1 : 0;
        pli.pPushConstantRanges    = (push.size > 0) ? &pcr : nullptr;
        if (vkCreatePipelineLayout(dev, &pli, nullptr, &out.pipeline_layout) != VK_SUCCESS) {
            vkDestroyDescriptorSetLayout(dev, out.set_layout, nullptr);
            vkDestroyShaderModule(dev, module, nullptr);
            out.set_layout = VK_NULL_HANDLE;
            return out;
        }
    }

    {
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage  = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = module;
        stage.pName  = entry_point;

        VkComputePipelineCreateInfo cpci{};
        cpci.sType  = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        cpci.stage  = stage;
        cpci.layout = out.pipeline_layout;

        VkResult rc = vkCreateComputePipelines(dev, pipeline_cache, 1, &cpci, nullptr, &out.pipeline);
        vkDestroyShaderModule(dev, module, nullptr);
        if (rc != VK_SUCCESS) {
            vkDestroyPipelineLayout(dev, out.pipeline_layout, nullptr);
            vkDestroyDescriptorSetLayout(dev, out.set_layout, nullptr);
            out.pipeline_layout = VK_NULL_HANDLE;
            out.set_layout      = VK_NULL_HANDLE;
            std::fprintf(stderr, "[br_core] vkCreateComputePipelines (device-explicit) failed (rc=%d) for %.*s\n",
                         static_cast<int>(rc),
                         static_cast<int>(canonical_name.size()), canonical_name.data());
            return out;
        }
    }

    return out;
}

} // namespace br_core
