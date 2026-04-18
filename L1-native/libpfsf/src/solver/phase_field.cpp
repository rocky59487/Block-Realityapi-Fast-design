#include "phase_field.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "core/constants.h"
#include "br_core/compute_pipeline.h"

#include <array>
#include <cstdio>

namespace pfsf {

namespace {
constexpr const char* kShaderName   = "compute/pfsf/phase_field_evolve.comp";
constexpr std::uint32_t kWorkgroupSize = 256;

std::uint32_t dispatchCount(std::int64_t N) {
    return static_cast<std::uint32_t>((N + kWorkgroupSize - 1) / kWorkgroupSize);
}
} // namespace

PhaseFieldSolver::PhaseFieldSolver(VulkanContext& vk) : vk_(vk) {}
PhaseFieldSolver::~PhaseFieldSolver() { destroyPipeline(); }

bool PhaseFieldSolver::createPipeline() {
    if (pipeline_.pipeline != VK_NULL_HANDLE) return true;

    std::vector<br_core::PipelineLayoutBinding> bindings = {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi (read-only)
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // hField (read-only ??smoother-owned writes)
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // dField (read-write)
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma (cond)
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // failFlags
        { 6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // hydration
    };

    pipeline_ = br_core::build_compute_pipeline(vk_.device(), VK_NULL_HANDLE, 
            kShaderName, bindings,
            { 0, sizeof(PhaseFieldPushConstants) });

    if (pipeline_.pipeline == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] phase_field pipeline build failed ??%s blob missing\n",
                     kShaderName);
        return false;
    }
    return true;
}

void PhaseFieldSolver::destroyPipeline() {
    br_core::destroy_compute_pipeline(vk_.device(), pipeline_);
}

void PhaseFieldSolver::recordEvolve(VkCommandBuffer cmd, IslandBuffer& buf,
                                     VkDescriptorPool pool,
                                     float l0, float gcBase, float relax,
                                     bool spectralSplit) {
    if (pipeline_.pipeline == VK_NULL_HANDLE) return;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return;
    if (buf.N() <= 0) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    VkBuffer phi = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    if (phi == VK_NULL_HANDLE || buf.h_field_buf == VK_NULL_HANDLE ||
        buf.d_field_buf == VK_NULL_HANDLE || buf.cond_buf == VK_NULL_HANDLE ||
        buf.type_buf == VK_NULL_HANDLE || buf.fail_buf == VK_NULL_HANDLE ||
        buf.hydration_buf == VK_NULL_HANDLE) {
        // Phase-field is optional per-island ??silently skip when buffers
        // are absent (IslandBuffer::allocate(with_phase_field=false)).
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
    buffers[0] = { phi,                 0, VK_WHOLE_SIZE };
    buffers[1] = { buf.h_field_buf,     0, VK_WHOLE_SIZE };
    buffers[2] = { buf.d_field_buf,     0, VK_WHOLE_SIZE };
    buffers[3] = { buf.cond_buf,        0, VK_WHOLE_SIZE };
    buffers[4] = { buf.type_buf,        0, VK_WHOLE_SIZE };
    buffers[5] = { buf.fail_buf,        0, VK_WHOLE_SIZE };
    buffers[6] = { buf.hydration_buf,   0, VK_WHOLE_SIZE };

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

    PhaseFieldPushConstants pc{};
    pc.Lx                   = static_cast<std::uint32_t>(buf.lx);
    pc.Ly                   = static_cast<std::uint32_t>(buf.ly);
    pc.Lz                   = static_cast<std::uint32_t>(buf.lz);
    pc.l0                   = l0;
    pc.gcBase               = gcBase;
    pc.relax                = relax;
    pc.spectralSplitEnabled = spectralSplit ? 1u : 0u;
    vkCmdPushConstants(cmd, pipeline_.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    vkCmdDispatch(cmd, dispatchCount(buf.N()), 1, 1);
}

} // namespace pfsf
