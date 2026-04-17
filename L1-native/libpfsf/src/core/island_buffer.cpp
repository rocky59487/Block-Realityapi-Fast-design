/**
 * @file island_buffer.cpp
 * @brief GPU buffer allocation/deallocation for a structure island.
 */
#include "island_buffer.h"
#include "vulkan_context.h"
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace pfsf {

static constexpr VkBufferUsageFlags STORAGE =
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

static constexpr VkBufferUsageFlags STAGING =
    VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

bool IslandBuffer::allocate(VulkanContext& vk, bool with_phase_field) {
    if (allocated) return true;
    int64_t n = N();
    if (n <= 0 || n > INT32_MAX) {  // sanity: GPU kernel indexing is still 32-bit
        fprintf(stderr, "[libpfsf] Island %d: N=%lld exceeds safe 32-bit limit\n",
                island_id, (long long)n);
        return false;
    }
    // Cast to int32_t for VkDeviceSize calculations below
    int32_t n32 = static_cast<int32_t>(n);

    VkDeviceSize f32n  = static_cast<VkDeviceSize>(n32) * sizeof(float);
    VkDeviceSize f32_6n = f32n * 6;   // conductivity SoA
    VkDeviceSize u8n   = static_cast<VkDeviceSize>(n32);

    bool ok = true;

    // Phi flip pair
    ok &= vk.allocBuffer(f32n, STORAGE, &phi_buf_a,   &phi_mem_a);
    ok &= vk.allocBuffer(f32n, STORAGE, &phi_buf_b,   &phi_mem_b);

    // Core fields
    ok &= vk.allocBuffer(f32n,   STORAGE, &source_buf,  &source_mem);
    ok &= vk.allocBuffer(f32_6n, STORAGE, &cond_buf,    &cond_mem);
    ok &= vk.allocBuffer(u8n,    STORAGE, &type_buf,    &type_mem);
    ok &= vk.allocBuffer(u8n,    STORAGE, &fail_buf,    &fail_mem);
    ok &= vk.allocBuffer(f32n,   STORAGE, &max_phi_buf, &max_phi_mem);
    ok &= vk.allocBuffer(f32n,   STORAGE, &rcomp_buf,   &rcomp_mem);
    ok &= vk.allocBuffer(f32n,   STORAGE, &rtens_buf,   &rtens_mem);

    // Hydration
    ok &= vk.allocBuffer(f32n, STORAGE, &hydration_buf, &hydration_mem);

    // Macro-block residual bits — 1 uint32 per voxel (shader packs bits into
    // atomicOr's at macro-block granularity; we over-provision for simplicity).
    VkDeviceSize u32n = static_cast<VkDeviceSize>(n32) * sizeof(std::uint32_t);
    ok &= vk.allocBuffer(u32n, STORAGE, &macro_residual_buf, &macro_residual_mem);

    // Phase-field (optional)
    if (with_phase_field) {
        ok &= vk.allocBuffer(f32n, STORAGE, &h_field_buf, &h_field_mem);
        ok &= vk.allocBuffer(f32n, STORAGE, &d_field_buf, &d_field_mem);
    }

    // Staging (max of failure readback size)
    VkDeviceSize staging_size = static_cast<VkDeviceSize>(
        (1 + 2000) * sizeof(int32_t));  // failCount + MAX_FAILURE_PER_TICK packed
    ok &= vk.allocBuffer(staging_size, STAGING, &staging_buf, &staging_mem);

    if (!ok) {
        fprintf(stderr, "[libpfsf] Failed to allocate buffers for island %d (N=%d)\n",
                island_id, n32);
        free(vk);
        return false;
    }

    allocated = true;
    return true;
}

bool IslandBuffer::allocatePCG(VulkanContext& vk) {
    if (hasPCGBuffers()) return true;
    int64_t n = N();
    if (n <= 0 || n > INT32_MAX) return false;

    VkDeviceSize f32n = static_cast<VkDeviceSize>(n) * sizeof(float);
    // Partial sums — one float per workgroup; WG_SCAN = 256 threads/WG, plus
    // headroom for two-pass reduction (pAp + r·z). 2*ceil(N/WG)+1 is ample.
    constexpr std::uint32_t kWG = 256;
    std::uint32_t groups = static_cast<std::uint32_t>((n + kWG - 1) / kWG);
    VkDeviceSize partialBytes = static_cast<VkDeviceSize>(2 * groups + 2) * sizeof(float);

    bool ok = true;
    ok &= vk.allocBuffer(f32n,         STORAGE, &pcg_r_buf,       &pcg_r_mem);
    ok &= vk.allocBuffer(f32n,         STORAGE, &pcg_z_buf,       &pcg_z_mem);
    ok &= vk.allocBuffer(f32n,         STORAGE, &pcg_p_buf,       &pcg_p_mem);
    ok &= vk.allocBuffer(f32n,         STORAGE, &pcg_ap_buf,      &pcg_ap_mem);
    ok &= vk.allocBuffer(partialBytes, STORAGE, &pcg_partial_buf, &pcg_partial_mem);

    if (!ok) {
        auto drop = [&](VkBuffer& b, VkDeviceMemory& m) {
            vk.freeBuffer(b, m); b = VK_NULL_HANDLE; m = VK_NULL_HANDLE;
        };
        drop(pcg_r_buf,       pcg_r_mem);
        drop(pcg_z_buf,       pcg_z_mem);
        drop(pcg_p_buf,       pcg_p_mem);
        drop(pcg_ap_buf,      pcg_ap_mem);
        drop(pcg_partial_buf, pcg_partial_mem);
        std::fprintf(stderr, "[libpfsf] PCG buffer allocation failed for island %d\n", island_id);
        return false;
    }
    return true;
}

bool IslandBuffer::uploadFromHosts(VulkanContext& vk) {
    if (!allocated || !hosts.registered) {
        std::fprintf(stderr, "[libpfsf] uploadFromHosts: island %d not allocated/registered\n", island_id);
        return false;
    }

    struct Field {
        const void*    src;
        VkDeviceSize   bytes;
        VkBuffer       dst;
        const char*    name;
    };
    // The phi buffer is always uploaded into the A-side of the flip pair —
    // a fresh registration resets phi_flip to false below.
    VkBuffer phi_dst = phi_buf_a;
    Field fields[] = {
        { hosts.phi,          static_cast<VkDeviceSize>(hosts.phi_bytes),          phi_dst,    "phi"         },
        { hosts.source,       static_cast<VkDeviceSize>(hosts.source_bytes),       source_buf, "source"      },
        { hosts.conductivity, static_cast<VkDeviceSize>(hosts.conductivity_bytes), cond_buf,   "conductivity"},
        { hosts.voxel_type,   static_cast<VkDeviceSize>(hosts.voxel_type_bytes),   type_buf,   "voxel_type"  },
        { hosts.rcomp,        static_cast<VkDeviceSize>(hosts.rcomp_bytes),        rcomp_buf,  "rcomp"       },
        { hosts.rtens,        static_cast<VkDeviceSize>(hosts.rtens_bytes),        rtens_buf,  "rtens"       },
    };

    VkCommandBuffer cmd = vk.allocCmdBuffer();
    if (cmd == VK_NULL_HANDLE) {
        std::fprintf(stderr, "[libpfsf] uploadFromHosts: allocCmdBuffer failed for island %d\n", island_id);
        return false;
    }

    // Allocate all staging buffers up-front so we can free them as a batch
    // after submit-wait. Per-field staging keeps the peak host memory
    // bounded to the single largest field (conductivity at 6N floats).
    std::vector<VkBuffer> stagingBufs;
    stagingBufs.reserve(sizeof(fields) / sizeof(fields[0]));

    for (const Field& f : fields) {
        if (f.src == nullptr || f.bytes <= 0 || f.dst == VK_NULL_HANDLE) continue;

        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory unusedMem = VK_NULL_HANDLE;
        if (!vk.allocBuffer(f.bytes,
                            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            &staging, &unusedMem)) {
            std::fprintf(stderr, "[libpfsf] uploadFromHosts: staging alloc failed for %s (island %d, %lld B)\n",
                         f.name, island_id, static_cast<long long>(f.bytes));
            for (VkBuffer b : stagingBufs) vk.freeBuffer(b, VK_NULL_HANDLE);
            // cmd is still open — end + discard. Vulkan will reclaim via pool
            // reset at shutdown; no explicit vkEndCommandBuffer needed here.
            return false;
        }

        void* mapped = vk.mapBuffer(staging, f.bytes);
        if (mapped == nullptr) {
            std::fprintf(stderr, "[libpfsf] uploadFromHosts: mapBuffer failed for %s (island %d)\n",
                         f.name, island_id);
            vk.freeBuffer(staging, VK_NULL_HANDLE);
            for (VkBuffer b : stagingBufs) vk.freeBuffer(b, VK_NULL_HANDLE);
            return false;
        }
        std::memcpy(mapped, f.src, f.bytes);
        vk.unmapBuffer(staging);

        VkBufferCopy region{};
        region.srcOffset = 0;
        region.dstOffset = 0;
        region.size      = f.bytes;
        vkCmdCopyBuffer(cmd, staging, f.dst, 1, &region);

        stagingBufs.push_back(staging);
    }

    // Transfer → compute-shader read barrier so the solver's first dispatch
    // observes the uploaded data.
    VkMemoryBarrier mb{};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);

    vk.submitAndWait(cmd);

    for (VkBuffer b : stagingBufs) vk.freeBuffer(b, VK_NULL_HANDLE);

    phi_flip = false;
    return true;
}

void IslandBuffer::free(VulkanContext& vk) {
    auto freeOne = [&](VkBuffer& buf, VkDeviceMemory& mem) {
        vk.freeBuffer(buf, mem);
        buf = VK_NULL_HANDLE;
        mem = VK_NULL_HANDLE;
    };

    freeOne(phi_buf_a,     phi_mem_a);
    freeOne(phi_buf_b,     phi_mem_b);
    freeOne(source_buf,    source_mem);
    freeOne(cond_buf,      cond_mem);
    freeOne(type_buf,      type_mem);
    freeOne(fail_buf,      fail_mem);
    freeOne(max_phi_buf,   max_phi_mem);
    freeOne(rcomp_buf,     rcomp_mem);
    freeOne(rtens_buf,     rtens_mem);
    freeOne(h_field_buf,      h_field_mem);
    freeOne(d_field_buf,      d_field_mem);
    freeOne(hydration_buf,    hydration_mem);
    freeOne(macro_residual_buf, macro_residual_mem);
    freeOne(pcg_r_buf,        pcg_r_mem);
    freeOne(pcg_z_buf,        pcg_z_mem);
    freeOne(pcg_p_buf,        pcg_p_mem);
    freeOne(pcg_ap_buf,       pcg_ap_mem);
    freeOne(pcg_partial_buf,  pcg_partial_mem);
    freeOne(staging_buf,      staging_mem);

    allocated = false;
}

} // namespace pfsf
