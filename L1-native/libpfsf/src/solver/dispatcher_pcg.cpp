/**
 * @file dispatcher_pcg.cpp
 * @brief PCG (Jacobi-preconditioned Conjugate Gradient) recording —
 *        1:1 port of Block Reality/api/src/main/java/com/blockreality/api/
 *        physics/pfsf/PFSFPCGRecorder.java.
 *
 * Each PCG step records 4 compute dispatches plus 2 two-pass dot
 * reductions, exactly matching PFSFPCGRecorder.recordPCGStep:
 *
 *   1) matvec     Ap = A₂₆·p
 *   2) dot(p,Ap)  → pcg_partial_buf  (per-WG)
 *                 → pass-2 reduce → pcg_reduction_buf scalar slot
 *   3) update     phi += alpha·p; r -= alpha·Ap; z = M⁻¹r; partials = r·z
 *                 (shader derives alpha from pcg_reduction_buf)
 *   4) dot reduce (r·z partials) → pcg_reduction_buf scalar slot
 *   5) direction  p = z + beta·p  (shader derives beta from reduction
 *                 buffer using the persisted rTz_old from step 6)
 *   6) rotate     vkCmdCopyBuffer within pcg_reduction_buf
 *                 (srcOffset=2·float → dstOffset=0) — persists the
 *                 fresh rTz to the "old" slot for the next iteration.
 *
 * Keeping the sequencing in this file mirrors the Java separation
 * between PFSFDispatcher (high-level) and PFSFPCGRecorder (helper).
 * The exact reduction-slot layout is defined by the GLSL shaders
 * (assets/blockreality/shaders/compute/pfsf/pcg_*.comp.glsl) — this
 * file is 1:1 with the Java recorder, so parity is automatic.
 */

#include "dispatcher.h"

#include "pcg_solver.h"
#include "core/island_buffer.h"
#include "core/vulkan_context.h"
#include "core/constants.h"

#include <array>
#include <cstdint>
#include <cstdio>

namespace pfsf {

namespace {

constexpr std::uint32_t kWGScan      = 256;
constexpr std::uint32_t kElPerWG     = 512;  // matches Java REDUCE_ELEMENTS_PER_WG

std::uint32_t ceilDiv(std::int64_t n, std::uint32_t wg) {
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

/** Pass-1 dot: sum(vecA[i] * vecB[i]) → partialBuf[workgroup]. */
void recordDotPass1(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                     const PCGSolver& pcg, IslandBuffer& buf,
                     VkBuffer vecA, VkBuffer vecB, VkBuffer partialBuf) {
    VkDescriptorSet set = allocSet(dev, pool, pcg.dotLayout());
    if (set == VK_NULL_HANDLE) return;
    writeStorage(dev, set, 0, vecA);
    writeStorage(dev, set, 1, vecB);
    writeStorage(dev, set, 2, partialBuf);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pcg.dotPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pcg.dotPipelineLayout(), 0, 1, &set, 0, nullptr);

    PCGDotPushConstants pc{};
    pc.N       = static_cast<std::uint32_t>(buf.N());
    pc.isPass2 = 0;
    vkCmdPushConstants(cmd, pcg.dotPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd, ceilDiv(buf.N(), kElPerWG), 1, 1);
    computeBarrier(cmd);
}

/** Pass-2 dot: reduce partial sums → reductionBuf[slot]. */
void recordDotPass2(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                     const PCGSolver& pcg, IslandBuffer& buf,
                     VkBuffer partialBuf, VkBuffer reductionBuf,
                     std::uint32_t numPartials, std::uint32_t slot) {
    VkDescriptorSet set = allocSet(dev, pool, pcg.dotLayout());
    if (set == VK_NULL_HANDLE) return;
    // In pass 2 the shader reads partials through binding 0; binding 1 is
    // ignored but still must be a valid storage buffer to satisfy the
    // descriptor-set layout.
    writeStorage(dev, set, 0, partialBuf);
    writeStorage(dev, set, 1, partialBuf);
    writeStorage(dev, set, 2, reductionBuf);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pcg.dotPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pcg.dotPipelineLayout(), 0, 1, &set, 0, nullptr);

    PCGDotPushConstants pc{};
    pc.N          = numPartials;
    pc.isPass2    = 1;
    pc.outputSlot = slot;
    pc.padding    = 0;
    vkCmdPushConstants(cmd, pcg.dotPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    // The pcg_dot shader writes pass-2 output to `partials[outputSlot]`
    // from workgroup 0; if groups2 > 1 multiple workgroups would race on
    // the same slot. Clamp here and rely on the reduction being small
    // enough that one workgroup (2·kWGSize = 512 elements) always covers
    // numPartials. For an island of N voxels, numPartials = ceil(N/512),
    // so N ≤ 262144 keeps us in the single-workgroup regime. Larger
    // islands would need a recursive pass-2 chain — not yet implemented.
    std::uint32_t groups2 = ceilDiv(numPartials, kElPerWG);
    if (groups2 == 0) groups2 = 1;
    if (groups2 > 1) {
        std::fprintf(stderr,
            "[libpfsf] pcg_dot pass2: numPartials=%u exceeds single-workgroup "
            "capacity (kElPerWG=%u); reduction would race. Clamping to 1 "
            "workgroup — result will be incorrect for this island.\n",
            numPartials, kElPerWG);
        groups2 = 1;
    }
    vkCmdDispatch(cmd, groups2, 1, 1);
    computeBarrier(cmd);
    (void) buf;
}

void recordMatvec(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                   const PCGSolver& pcg, IslandBuffer& buf,
                   VkBuffer input, VkBuffer output) {
    VkDescriptorSet set = allocSet(dev, pool, pcg.matvecLayout());
    if (set == VK_NULL_HANDLE) return;
    writeStorage(dev, set, 0, input);
    writeStorage(dev, set, 1, output);
    writeStorage(dev, set, 2, buf.cond_buf);
    writeStorage(dev, set, 3, buf.type_buf);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pcg.matvecPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pcg.matvecPipelineLayout(), 0, 1, &set, 0, nullptr);

    PCGMatvecPushConstants pc{};
    pc.Lx = static_cast<std::uint32_t>(buf.lx);
    pc.Ly = static_cast<std::uint32_t>(buf.ly);
    pc.Lz = static_cast<std::uint32_t>(buf.lz);
    vkCmdPushConstants(cmd, pcg.matvecPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd, ceilDiv(buf.N(), kWGScan), 1, 1);
    computeBarrier(cmd);
}

void recordUpdate(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                   const PCGSolver& pcg, IslandBuffer& buf, bool isInit,
                   float alphaInit) {
    VkDescriptorSet set = allocSet(dev, pool, pcg.updateLayout());
    if (set == VK_NULL_HANDLE) return;
    VkBuffer phi = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;
    writeStorage(dev, set, 0, phi);
    writeStorage(dev, set, 1, buf.pcg_r_buf);
    writeStorage(dev, set, 2, buf.pcg_p_buf);
    writeStorage(dev, set, 3, buf.pcg_ap_buf);
    writeStorage(dev, set, 4, buf.source_buf);
    writeStorage(dev, set, 5, buf.type_buf);
    writeStorage(dev, set, 6, buf.pcg_partial_buf);
    writeStorage(dev, set, 7, buf.pcg_reduction_buf);
    writeStorage(dev, set, 8, buf.cond_buf);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pcg.updatePipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pcg.updatePipelineLayout(), 0, 1, &set, 0, nullptr);

    PCGUpdatePushConstants pc{};
    pc.Lx      = static_cast<std::uint32_t>(buf.lx);
    pc.Ly      = static_cast<std::uint32_t>(buf.ly);
    pc.Lz      = static_cast<std::uint32_t>(buf.lz);
    pc.alpha   = alphaInit;
    pc.isInit  = isInit ? 1u : 0u;
    pc.padding = 0;
    vkCmdPushConstants(cmd, pcg.updatePipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd, ceilDiv(buf.N(), kWGScan), 1, 1);
    computeBarrier(cmd);
}

void recordDirection(VkCommandBuffer cmd, VkDevice dev, VkDescriptorPool pool,
                      const PCGSolver& pcg, IslandBuffer& buf) {
    VkDescriptorSet set = allocSet(dev, pool, pcg.directionLayout());
    if (set == VK_NULL_HANDLE) return;
    writeStorage(dev, set, 0, buf.pcg_r_buf);
    writeStorage(dev, set, 1, buf.pcg_p_buf);
    writeStorage(dev, set, 2, buf.type_buf);
    writeStorage(dev, set, 3, buf.pcg_reduction_buf);
    writeStorage(dev, set, 4, buf.cond_buf);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pcg.directionPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pcg.directionPipelineLayout(), 0, 1, &set, 0, nullptr);

    PCGDirectionPushConstants pc{};
    pc.Lx = static_cast<std::uint32_t>(buf.lx);
    pc.Ly = static_cast<std::uint32_t>(buf.ly);
    pc.Lz = static_cast<std::uint32_t>(buf.lz);
    vkCmdPushConstants(cmd, pcg.directionPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);

    vkCmdDispatch(cmd, ceilDiv(buf.N(), kWGScan), 1, 1);
    computeBarrier(cmd);
}

/** Copy reductionBuf[2] → reductionBuf[0] so the next PCG step reads
 *  the freshly-computed rTz as its "old" value. */
void recordReductionRotate(VkCommandBuffer cmd, IslandBuffer& buf) {
    if (buf.pcg_reduction_buf == VK_NULL_HANDLE) return;

    // Barrier: prior update/direction finished writing reductionBuf,
    // transfer stage is about to read slot 2.
    VkMemoryBarrier pre{};
    pre.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    pre.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 1, &pre, 0, nullptr, 0, nullptr);

    VkBufferCopy region{};
    region.srcOffset = 2 * sizeof(float);
    region.dstOffset = 0;
    region.size      = sizeof(float);
    vkCmdCopyBuffer(cmd, buf.pcg_reduction_buf, buf.pcg_reduction_buf, 1, &region);

    // Barrier: transfer finished, next step's compute reads slot 0.
    VkMemoryBarrier post{};
    post.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &post, 0, nullptr, 0, nullptr);
}

} // namespace

void Dispatcher::recordPCGInitialResidual(VkCommandBuffer cmd, IslandBuffer& buf,
                                           VkDescriptorPool pool) {
    if (!pcg_.isReady() || !buf.hasPCGBuffers()) return;
    if (buf.cond_buf == VK_NULL_HANDLE || buf.type_buf == VK_NULL_HANDLE ||
        buf.source_buf == VK_NULL_HANDLE) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    VkBuffer phi = buf.phi_flip ? buf.phi_buf_b : buf.phi_buf_a;

    // 1) Ap = A·phi (reusing matvec with phi as the input vector).
    recordMatvec(cmd, dev, pool, pcg_, buf, phi, buf.pcg_ap_buf);

    // 2) update in "init" mode: computes r = source - Ap, z = M⁻¹r,
    //    p = z, and writes r·z partial sums. alpha push-constant is
    //    unused but the shader reads it as the init-residual marker
    //    (-1.0 mirrors Java PFSFPCGRecorder.computeInitialResidual).
    recordUpdate(cmd, dev, pool, pcg_, buf, /*isInit=*/true, /*alpha=*/-1.0f);

    // 3) Reduce partial r·z → reductionBuf[0] (rTz_old for iteration 1).
    const std::uint32_t groups = ceilDiv(buf.N(), kElPerWG);
    recordDotPass2(cmd, dev, pool, pcg_, buf,
                    buf.pcg_partial_buf, buf.pcg_reduction_buf, groups, 0);
}

void Dispatcher::recordPCGStep(VkCommandBuffer cmd, IslandBuffer& buf,
                                VkDescriptorPool pool) {
    if (!pcg_.isReady() || !buf.hasPCGBuffers()) return;

    VkDevice dev = vk_.device();
    if (dev == VK_NULL_HANDLE) return;

    const std::uint32_t groups = ceilDiv(buf.N(), kElPerWG);

    // 1) Ap = A·p
    recordMatvec(cmd, dev, pool, pcg_, buf, buf.pcg_p_buf, buf.pcg_ap_buf);

    // 2) dot(p, Ap) → partials, reduce scalar into reductionBuf[1].
    //    The pcg_dot shader always stores at partials[0]; pcg_update
    //    reads alpha = rTz_old / pAp by consuming both slot 1 (current
    //    pAp) and the rTz_old in slot 0.
    recordDotPass1(cmd, dev, pool, pcg_, buf,
                    buf.pcg_p_buf, buf.pcg_ap_buf, buf.pcg_partial_buf);
    recordDotPass2(cmd, dev, pool, pcg_, buf,
                    buf.pcg_partial_buf, buf.pcg_reduction_buf, groups, 1);

    // 3) update: phi += α·p, r -= α·Ap, z = M⁻¹r, partials = r·z.
    //    shader derives α from reductionBuf internally (rTz_old/pAp).
    recordUpdate(cmd, dev, pool, pcg_, buf, /*isInit=*/false, /*alpha=*/0.0f);

    // 4) reduce new r·z partials → reductionBuf[2] = rTz_new. pcg_direction
    //    reads β = rTz_new / rTz_old from slots 2 and 0.
    recordDotPass2(cmd, dev, pool, pcg_, buf,
                    buf.pcg_partial_buf, buf.pcg_reduction_buf, groups, 2);

    // 5) direction: p = z + β·p, with β = rTz_new / rTz_old.
    recordDirection(cmd, dev, pool, pcg_, buf);

    // 6) rotate rTz_new → rTz_old so the next step's β is well-defined.
    recordReductionRotate(cmd, buf);
}

} // namespace pfsf
