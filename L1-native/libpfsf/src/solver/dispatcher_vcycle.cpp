/**
 * @file dispatcher_vcycle.cpp
 * @brief V-Cycle multigrid recording — 1:1 port of
 *        Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/
 *        PFSFVCycleRecorder.recordVCycle (L1-only variant).
 *
 * Sequence per sweep:
 *   1) pre-smooth  — 1 RBGS step on the fine grid
 *   2) restrict    — fine → L1 via mg_restrict.comp
 *   3) coarse RBGS — 4 Red/Black passes on L1 via jacobi_smooth.comp
 *   4) prolong     — L1 → fine via mg_prolong.comp  (adds correction)
 *   5) post-smooth — 1 RBGS step on the fine grid
 *
 * W-Cycle (recursive L1 ↔ L2) is a follow-up: IslandBuffer already owns
 * the L2 buffer set (M2j) so that layer can land as a self-contained
 * addition once its restrict/prolong shader bindings are proven.
 *
 * The coarse RBGS dispatches use jacobi_smooth.comp — same 26-connectivity
 * stencil as the fine-grid rbgs_smooth, which CLAUDE.md marks as the
 * cross-shader convergence invariant.
 */

#include "dispatcher.h"

#include "jacobi_solver.h"
#include "vcycle_solver.h"
#include "core/island_buffer.h"
#include "core/vulkan_context.h"
#include "core/constants.h"

#include <array>
#include <cstdint>
#include <cstdio>

namespace pfsf {

namespace {

constexpr std::uint32_t kWGScan = 256;   // matches mg_restrict local_size_x
constexpr std::uint32_t kWGX    = 8;     // matches jacobi_smooth / mg_prolong
constexpr std::uint32_t kWGY    = 8;
constexpr std::uint32_t kWGZ    = 4;

std::uint32_t ceilDivU(std::int64_t n, std::uint32_t wg) {
    return static_cast<std::uint32_t>((n + wg - 1) / wg);
}

void computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb{};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);
}

VkDescriptorSet allocSet(VkDevice dev, VkDescriptorPool pool,
                          VkDescriptorSetLayout layout) {
    if (dev == VK_NULL_HANDLE || pool == VK_NULL_HANDLE || layout == VK_NULL_HANDLE) {
        return VK_NULL_HANDLE;
    }
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{};
    ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool     = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts        = &layout;
    if (vkAllocateDescriptorSets(dev, &ai, &set) != VK_SUCCESS) return VK_NULL_HANDLE;
    return set;
}

void writeStorage(VkDevice dev, VkDescriptorSet set,
                   std::uint32_t binding, VkBuffer buf) {
    VkDescriptorBufferInfo bi{ buf, 0, VK_WHOLE_SIZE };
    VkWriteDescriptorSet w{};
    w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w.dstSet          = set;
    w.dstBinding      = binding;
    w.descriptorCount = 1;
    w.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    w.pBufferInfo     = &bi;
    vkUpdateDescriptorSets(dev, 1, &w, 0, nullptr);
}

/** mg_restrict dispatch — reads fine phi/source/cond/type, writes coarse
 *  phi/source. Coarse cond/type are populated separately (data-builder
 *  uploadCoarseData in Java); this shader does not touch them. */
void recordRestrict(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                     const VCycleSolver& vc, IslandBuffer& buf) {
    VkDescriptorSet set = allocSet(dev, pool, vc.restrictLayout());
    if (set == VK_NULL_HANDLE) return;

    VkBuffer phi_fine = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    writeStorage(dev, set, 0, phi_fine);
    writeStorage(dev, set, 1, buf.source_buf);
    writeStorage(dev, set, 2, buf.cond_buf);
    writeStorage(dev, set, 3, buf.type_buf);
    writeStorage(dev, set, 4, buf.mg_phi_l1);
    writeStorage(dev, set, 5, buf.mg_source_l1);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vc.restrictPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vc.restrictPipelineLayout(), 0, 1, &set, 0, nullptr);

    MGPushConstants pc{};
    pc.Lx_fine   = static_cast<std::uint32_t>(buf.lx);
    pc.Ly_fine   = static_cast<std::uint32_t>(buf.ly);
    pc.Lz_fine   = static_cast<std::uint32_t>(buf.lz);
    pc.Lx_coarse = static_cast<std::uint32_t>(buf.lx_l1);
    pc.Ly_coarse = static_cast<std::uint32_t>(buf.ly_l1);
    pc.Lz_coarse = static_cast<std::uint32_t>(buf.lz_l1);
    vkCmdPushConstants(cmd, vc.restrictPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd, ceilDivU(buf.nL1(), kWGScan), 1, 1);
    computeBarrier(cmd);
}

/** mg_prolong dispatch — adds coarse correction back onto the fine phi. */
void recordProlong(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                    const VCycleSolver& vc, IslandBuffer& buf) {
    VkDescriptorSet set = allocSet(dev, pool, vc.prolongLayout());
    if (set == VK_NULL_HANDLE) return;

    VkBuffer phi_fine = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    writeStorage(dev, set, 0, phi_fine);
    writeStorage(dev, set, 1, buf.mg_phi_l1);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vc.prolongPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vc.prolongPipelineLayout(), 0, 1, &set, 0, nullptr);

    MGPushConstants pc{};
    pc.Lx_fine   = static_cast<std::uint32_t>(buf.lx);
    pc.Ly_fine   = static_cast<std::uint32_t>(buf.ly);
    pc.Lz_fine   = static_cast<std::uint32_t>(buf.lz);
    pc.Lx_coarse = static_cast<std::uint32_t>(buf.lx_l1);
    pc.Ly_coarse = static_cast<std::uint32_t>(buf.ly_l1);
    pc.Lz_coarse = static_cast<std::uint32_t>(buf.lz_l1);
    vkCmdPushConstants(cmd, vc.prolongPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd,
        ceilDivU(buf.lx, kWGX),
        ceilDivU(buf.ly, kWGY),
        ceilDivU(buf.lz, kWGZ));
    computeBarrier(cmd);
}

/** Single Red/Black pass of jacobi_smooth on L1 using the coarse-grid
 *  pipeline owned by VCycleSolver. */
void recordCoarseRBGSPass(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                           const VCycleSolver& vc, IslandBuffer& buf,
                           std::uint32_t redBlackPass, float damping) {
    VkDescriptorSet set = allocSet(dev, pool, vc.coarseRBGSLayout());
    if (set == VK_NULL_HANDLE) return;

    writeStorage(dev, set, 0, buf.mg_phi_l1);
    writeStorage(dev, set, 1, buf.mg_phi_prev_l1 != VK_NULL_HANDLE
                              ? buf.mg_phi_prev_l1 : buf.mg_phi_l1);
    writeStorage(dev, set, 2, buf.mg_source_l1);
    writeStorage(dev, set, 3, buf.mg_cond_l1);
    writeStorage(dev, set, 4, buf.mg_type_l1);
    // hField binding is optional on the coarse level — jacobi_smooth
    // always writes to it, so we bind mg_phi_l1 as a harmless sink when
    // the phase-field feature is off. Parity with PFSFVCycleRecorder:
    // Java always binds a live SSBO (getHFieldBuf()), which may point
    // to a placeholder on phase-field-off islands.
    VkBuffer hfield = (buf.h_field_buf != VK_NULL_HANDLE)
                     ? buf.h_field_buf : buf.mg_phi_l1;
    writeStorage(dev, set, 5, hfield);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vc.coarseRBGSPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vc.coarseRBGSPipelineLayout(), 0, 1, &set, 0, nullptr);

    CoarseRBGSPushConstants pc{};
    pc.Lx            = static_cast<std::uint32_t>(buf.lx_l1);
    pc.Ly            = static_cast<std::uint32_t>(buf.ly_l1);
    pc.Lz            = static_cast<std::uint32_t>(buf.lz_l1);
    pc.omega         = 1.0f;                    // Jacobi relaxation = identity
    pc.rho_spec      = 0.0f;                    // rho_spec override — 0 = auto
    pc.iter          = static_cast<std::uint32_t>(buf.chebyshev_iter);
    pc.damping       = damping;
    pc.redBlackPass  = redBlackPass;
    vkCmdPushConstants(cmd, vc.coarseRBGSPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd,
        ceilDivU(buf.lx_l1, kWGX),
        ceilDivU(buf.ly_l1, kWGY),
        ceilDivU(buf.lz_l1, kWGZ));
    computeBarrier(cmd);
}

} // namespace

int Dispatcher::recordVCycle(VkCommandBuffer cmd, IslandBuffer& buf,
                              VkDescriptorPool pool) {
    if (!vcycle_.isReady() || !rbgs_.isReady()) return 0;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE || buf.N() <= 0) return 0;
    if (!buf.hasMultigridL1()) return 0;   // caller must allocateMultigrid first

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return 0;

    // Chebyshev damping mirrors the fine-grid dispatcher helper — the
    // warmup threshold is identical so coarse-level damping advances in
    // lockstep with the fine grid (otherwise the coarse solve would
    // over-damp during early ticks and smear high-frequency modes that
    // the subsequent prolong would have to re-converge).
    const float damping = (buf.chebyshev_iter >= WARMUP_STEPS)
                        ? DAMPING_FACTOR : 0.0f;

    // 1) Pre-smooth on the fine grid.
    rbgs_.recordStep(cmd, buf, pool, damping);
    computeBarrier(cmd);

    // 2) Restrict fine → L1.
    recordRestrict(cmd, dev, pool, vcycle_, buf);

    // 3) Four coarse RBGS passes (Red, Black, Red, Black) — matches
    //    Java PFSFVCycleRecorder.recordVCycle fallback path (no L2).
    for (int i = 0; i < 4; ++i) {
        const std::uint32_t pass = static_cast<std::uint32_t>(i & 1);
        recordCoarseRBGSPass(cmd, dev, pool, vcycle_, buf, pass, damping);
    }

    // 4) Prolong L1 → fine (adds correction).
    recordProlong(cmd, dev, pool, vcycle_, buf);

    // 5) Post-smooth on the fine grid.
    rbgs_.recordStep(cmd, buf, pool, damping);
    computeBarrier(cmd);

    // Fine-grid smooths count towards the dispatcher step budget so the
    // caller can keep MG_INTERVAL scheduling in sync with the Java path.
    return 2;
}

} // namespace pfsf
