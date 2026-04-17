#include "failure_scan.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "br_core/compute_pipeline.h"

#include <array>
#include <cstdio>

namespace pfsf {

namespace {
constexpr const char* kShaderName   = "compute/pfsf/failure_scan.comp";
constexpr std::uint32_t kWorkgroupSize = 256;

std::uint32_t dispatchCount(std::int64_t N) {
    return static_cast<std::uint32_t>((N + kWorkgroupSize - 1) / kWorkgroupSize);
}
} // namespace

FailureScan::FailureScan(VulkanContext& vk) : vk_(vk) {}
FailureScan::~FailureScan() { destroyPipeline(); }

bool FailureScan::createPipeline() {
    if (pipeline_.pipeline != VK_NULL_HANDLE) return true;

    std::vector<br_core::DescriptorBinding> bindings = {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // maxPhi
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rcomp
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // fail_flags
        { 6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rtens
        { 7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // macroResidualBits
        { 8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // source (rho)
    };

    pipeline_ = br_core::build_compute_pipeline(
            kShaderName, bindings,
            { 0, sizeof(FailureScanPushConstants) });

    if (pipeline_.pipeline == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] failure_scan pipeline build failed — %s blob missing\n",
                     kShaderName);
        return false;
    }
    return true;
}

void FailureScan::destroyPipeline() {
    br_core::destroy_compute_pipeline(pipeline_);
}

void FailureScan::recordStep(VkCommandBuffer cmd, IslandBuffer& buf,
                              VkDescriptorPool pool, float phi_orphan) {
    if (pipeline_.pipeline == VK_NULL_HANDLE) return;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return;
    if (buf.N() <= 0) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    // Phi is whichever side of the flip is current.
    VkBuffer phi = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    if (phi == VK_NULL_HANDLE || buf.source_buf == VK_NULL_HANDLE ||
        buf.cond_buf == VK_NULL_HANDLE || buf.type_buf == VK_NULL_HANDLE ||
        buf.fail_buf == VK_NULL_HANDLE || buf.max_phi_buf == VK_NULL_HANDLE ||
        buf.rcomp_buf == VK_NULL_HANDLE || buf.rtens_buf == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] failure_scan recordStep: island %d missing buffers\n",
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

    std::array<VkDescriptorBufferInfo, 9> buffers{};
    buffers[0] = { phi,                 0, VK_WHOLE_SIZE };
    buffers[1] = { buf.cond_buf,        0, VK_WHOLE_SIZE };
    buffers[2] = { buf.max_phi_buf,     0, VK_WHOLE_SIZE };
    buffers[3] = { buf.rcomp_buf,       0, VK_WHOLE_SIZE };
    buffers[4] = { buf.type_buf,        0, VK_WHOLE_SIZE };
    buffers[5] = { buf.fail_buf,        0, VK_WHOLE_SIZE };
    buffers[6] = { buf.rtens_buf,       0, VK_WHOLE_SIZE };
    // macroResidualBits — dedicated SSBO shared with the RBGS binding 5.
    // Falls back to phi (read-only filler) if the island predates this
    // field; the shader will write noise to phi but won't crash validation.
    VkBuffer macro = (buf.macro_residual_buf != VK_NULL_HANDLE)
                   ? buf.macro_residual_buf : phi;
    buffers[7] = { macro,               0, VK_WHOLE_SIZE };
    buffers[8] = { buf.source_buf,      0, VK_WHOLE_SIZE };

    std::array<VkWriteDescriptorSet, 9> writes{};
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

    FailureScanPushConstants pc{};
    pc.Lx         = static_cast<std::uint32_t>(buf.lx);
    pc.Ly         = static_cast<std::uint32_t>(buf.ly);
    pc.Lz         = static_cast<std::uint32_t>(buf.lz);
    pc.phi_orphan = phi_orphan;
    vkCmdPushConstants(cmd, pipeline_.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    vkCmdDispatch(cmd, dispatchCount(buf.N()), 1, 1);
}

} // namespace pfsf
