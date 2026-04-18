/**
 * @file jacobi_solver.cpp
 * @brief RBGS 8-colour in-place smoother ??real dispatch via br_core.
 */
#include "jacobi_solver.h"
#include "core/vulkan_context.h"
#include "core/island_buffer.h"
#include "core/constants.h"
#include "br_core/compute_pipeline.h"

#include <array>
#include <cstdio>
#include <cstdint>

namespace pfsf {

namespace {

constexpr const char* kShaderName = "compute/pfsf/rbgs_smooth.comp";
constexpr std::uint32_t kWorkgroupSize = 256;  // matches local_size_x in GLSL

std::uint32_t dispatchCount(std::int64_t N) {
    return static_cast<std::uint32_t>((N + kWorkgroupSize - 1) / kWorkgroupSize);
}

void fullMemoryBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb{};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);
}

} // namespace

JacobiSolver::JacobiSolver(VulkanContext& vk) : vk_(vk) {}

JacobiSolver::~JacobiSolver() {
    destroyPipeline();
}

bool JacobiSolver::createPipeline() {
    if (pipeline_.pipeline != VK_NULL_HANDLE) return true;

    // Binding table must mirror rbgs_smooth.comp.glsl exactly.
    std::vector<br_core::PipelineLayoutBinding> bindings = {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi (in-place)
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // source
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // conductivity (SoA, 6N)
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // type
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // hField
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // macroResidualBits
    };

    br_core::PushConstantRange pcr{};
    pcr.offset = 0;
    pcr.size   = sizeof(RBGSPushConstants);

    pipeline_ = br_core::build_compute_pipeline(vk_.device(), VK_NULL_HANDLE, kShaderName, bindings, pcr);
    if (pipeline_.pipeline == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] RBGS pipeline build failed ??%s blob missing or Vulkan error\n",
                     kShaderName);
        return false;
    }
    return true;
}

void JacobiSolver::destroyPipeline() {
    br_core::destroy_compute_pipeline(vk_.device(), pipeline_);
}

void JacobiSolver::recordStep(VkCommandBuffer cmd, IslandBuffer& buf,
                               VkDescriptorPool pool, float damping) {
    if (pipeline_.pipeline == VK_NULL_HANDLE) return;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return;
    if (buf.N() <= 0) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    // Pick the current phi (flip-buffer). RBGS is in-place but we still
    // use the flip A/B pair so upstream V-cycle restriction can keep the
    // previous state; the shader writes to whichever side is marked current.
    VkBuffer phi = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    if (phi == VK_NULL_HANDLE || buf.source_buf == VK_NULL_HANDLE ||
        buf.cond_buf == VK_NULL_HANDLE || buf.type_buf == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] RBGS recordStep: island %d has missing GPU buffers\n",
                     buf.island_id);
        return;
    }

    // Allocate one descriptor set per sweep. Long-term this should come
    // from br_core::DescriptorCache so the 8 colour passes in a single
    // tick reuse the same set ??for M2a the simpler path is 1 alloc,
    // reset at tick boundary by the caller.
    VkDescriptorSet set = VK_NULL_HANDLE;
    {
        VkDescriptorSetAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool     = pool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts        = &pipeline_.set_layout;
        if (vkAllocateDescriptorSets(dev, &ai, &set) != VK_SUCCESS || set == VK_NULL_HANDLE) {
            std::fprintf(stderr, "[libpfsf] RBGS: vkAllocateDescriptorSets failed (pool exhausted?)\n");
            return;
        }
    }

    // Bind all six SSBOs. Offsets = 0, range = WHOLE ??island buffers are
    // sized by the island's N so the shader's bounds checks cover the rest.
    std::array<VkDescriptorBufferInfo, 6> buffers{};
    buffers[0] = { phi,                 0, VK_WHOLE_SIZE };
    buffers[1] = { buf.source_buf,      0, VK_WHOLE_SIZE };
    buffers[2] = { buf.cond_buf,        0, VK_WHOLE_SIZE };
    buffers[3] = { buf.type_buf,        0, VK_WHOLE_SIZE };
    // hField (binding 4) is always a dedicated scratch sink —
    // island_buffer.cpp allocates it unconditionally so the shader's
    // `hField[i] = max(hField[i], psi_e)` write never lands in phi.
    if (buf.h_field_buf == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] RBGS recordStep: island %d h_field_buf unallocated (scratch slot required)\n",
                     buf.island_id);
        return;
    }
    buffers[4] = { buf.h_field_buf, 0, VK_WHOLE_SIZE };
    // Binding 5 = macroResidualBits (per-macro-block residual accumulator).
    // Must be a dedicated SSBO; aliasing onto fail_buf corrupts the
    // per-voxel failure codes that failure_scan later reads.
    VkBuffer macro = (buf.macro_residual_buf != VK_NULL_HANDLE)
                   ? buf.macro_residual_buf : phi;
    buffers[5] = { macro, 0, VK_WHOLE_SIZE };

    std::array<VkWriteDescriptorSet, 6> writes{};
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

    const std::uint32_t groups = dispatchCount(buf.N());

    // Sweep all 8 colours ??octree (x%2)|(y%2)<<1|(z%2)<<2.
    for (std::uint32_t color = 0; color < 8; ++color) {
        RBGSPushConstants pc{};
        pc.Lx        = static_cast<std::uint32_t>(buf.lx);
        pc.Ly        = static_cast<std::uint32_t>(buf.ly);
        pc.Lz        = static_cast<std::uint32_t>(buf.lz);
        pc.colorPass = color;
        pc.damping   = damping;
        vkCmdPushConstants(cmd, pipeline_.pipeline_layout,
                           VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
        vkCmdDispatch(cmd, groups, 1, 1);
        // Each colour pass must finish before the next reads its neighbours.
        if (color < 7) fullMemoryBarrier(cmd);
    }

    buf.chebyshev_iter++;
}

} // namespace pfsf
