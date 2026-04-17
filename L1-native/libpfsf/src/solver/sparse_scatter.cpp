#include "sparse_scatter.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "br_core/compute_pipeline.h"

#include <array>
#include <cstdio>
#include <vector>

namespace pfsf {

namespace {
constexpr const char*     kShaderName    = "compute/pfsf/sparse_scatter.comp";
constexpr std::uint32_t   kWorkgroupSize = 64;  // matches local_size_x=64

std::uint32_t dispatchCount(std::uint32_t updates) {
    return (updates + kWorkgroupSize - 1) / kWorkgroupSize;
}
} // namespace

SparseScatterSolver::SparseScatterSolver(VulkanContext& vk) : vk_(vk) {}
SparseScatterSolver::~SparseScatterSolver() { destroyPipeline(); }

bool SparseScatterSolver::createPipeline() {
    if (pipeline_.pipeline != VK_NULL_HANDLE) return true;

    // Binding order mirrors sparse_scatter.comp.glsl:
    //   0: Updates       (readonly, host-visible upload SSBO)
    //   1: Source        (device-local)
    //   2: Conductivity  (device-local, SoA 6×N)
    //   3: Type          (device-local uint array)
    //   4: MaxPhiBuf     (device-local)
    //   5: RcompBuf      (device-local)
    //   6: RtensBuf      (device-local)
    std::vector<br_core::DescriptorBinding> bindings = {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
        { 6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },
    };

    pipeline_ = br_core::build_compute_pipeline(
            kShaderName, bindings,
            { 0, sizeof(SparseScatterPushConstants) });

    if (pipeline_.pipeline == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] sparse_scatter pipeline build failed — %s blob missing\n",
                     kShaderName);
        return false;
    }
    return true;
}

void SparseScatterSolver::destroyPipeline() {
    br_core::destroy_compute_pipeline(pipeline_);
}

void SparseScatterSolver::recordScatter(VkCommandBuffer cmd, IslandBuffer& buf,
                                         VkDescriptorPool pool,
                                         std::uint32_t updateCount) {
    if (pipeline_.pipeline == VK_NULL_HANDLE) return;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return;
    if (updateCount == 0 || buf.N() <= 0) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    if (buf.sparse_upload_buf == VK_NULL_HANDLE ||
        buf.source_buf        == VK_NULL_HANDLE ||
        buf.cond_buf          == VK_NULL_HANDLE ||
        buf.type_buf          == VK_NULL_HANDLE ||
        buf.max_phi_buf       == VK_NULL_HANDLE ||
        buf.rcomp_buf         == VK_NULL_HANDLE ||
        buf.rtens_buf         == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] sparse_scatter: island %d missing buffers\n",
                     buf.island_id);
        return;
    }

    VkDescriptorSet set = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{};
    ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool     = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts        = &pipeline_.set_layout;
    if (vkAllocateDescriptorSets(dev, &ai, &set) != VK_SUCCESS || set == VK_NULL_HANDLE) return;

    std::array<VkDescriptorBufferInfo, 7> buffers{};
    buffers[0] = { buf.sparse_upload_buf, 0, VK_WHOLE_SIZE };
    buffers[1] = { buf.source_buf,        0, VK_WHOLE_SIZE };
    buffers[2] = { buf.cond_buf,          0, VK_WHOLE_SIZE };
    buffers[3] = { buf.type_buf,          0, VK_WHOLE_SIZE };
    buffers[4] = { buf.max_phi_buf,       0, VK_WHOLE_SIZE };
    buffers[5] = { buf.rcomp_buf,         0, VK_WHOLE_SIZE };
    buffers[6] = { buf.rtens_buf,         0, VK_WHOLE_SIZE };

    std::array<VkWriteDescriptorSet, 7> writes{};
    for (std::uint32_t i = 0; i < writes.size(); ++i) {
        writes[i].sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet          = set;
        writes[i].dstBinding      = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo     = &buffers[i];
    }
    vkUpdateDescriptorSets(dev,
                           static_cast<std::uint32_t>(writes.size()), writes.data(),
                           0, nullptr);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_.pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            pipeline_.pipeline_layout, 0, 1, &set, 0, nullptr);

    SparseScatterPushConstants pc{};
    pc.updateCount = updateCount;
    pc.totalN      = static_cast<std::uint32_t>(buf.N());
    vkCmdPushConstants(cmd, pipeline_.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    vkCmdDispatch(cmd, dispatchCount(updateCount), 1, 1);
}

} // namespace pfsf
