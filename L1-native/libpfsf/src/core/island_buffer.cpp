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

    // Dot-product reduction scratch — one float per workgroup; matches
    // Java PFSFPCGRecorder REDUCE_ELEMENTS_PER_WG=512. The reduction
    // scalars (rTz_old / pAp / rTz_new / spare) live in a separate
    // PCG_REDUCTION_SLOTS-sized buffer shared across dispatches.
    constexpr std::uint32_t kElPerWG = 512;
    std::uint32_t groups = static_cast<std::uint32_t>(
        (n + kElPerWG - 1) / kElPerWG);
    if (groups == 0) groups = 1;
    VkDeviceSize partialBytes   = static_cast<VkDeviceSize>(groups) * sizeof(float);
    VkDeviceSize reductionBytes = static_cast<VkDeviceSize>(4) * sizeof(float);

    bool ok = true;
    ok &= vk.allocBuffer(f32n,           STORAGE, &pcg_r_buf,         &pcg_r_mem);
    ok &= vk.allocBuffer(f32n,           STORAGE, &pcg_z_buf,         &pcg_z_mem);
    ok &= vk.allocBuffer(f32n,           STORAGE, &pcg_p_buf,         &pcg_p_mem);
    ok &= vk.allocBuffer(f32n,           STORAGE, &pcg_ap_buf,        &pcg_ap_mem);
    ok &= vk.allocBuffer(partialBytes,   STORAGE, &pcg_partial_buf,   &pcg_partial_mem);
    // Reduction buffer needs TRANSFER_SRC|DST for the rTz rotation copy
    // (reductionBuf[2] → reductionBuf[0]) between PCG iterations.
    ok &= vk.allocBuffer(reductionBytes,
                         STORAGE
                         | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                         | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                         &pcg_reduction_buf, &pcg_reduction_mem);

    if (!ok) {
        auto drop = [&](VkBuffer& b, VkDeviceMemory& m) {
            vk.freeBuffer(b, m); b = VK_NULL_HANDLE; m = VK_NULL_HANDLE;
        };
        drop(pcg_r_buf,         pcg_r_mem);
        drop(pcg_z_buf,         pcg_z_mem);
        drop(pcg_p_buf,         pcg_p_mem);
        drop(pcg_ap_buf,        pcg_ap_mem);
        drop(pcg_partial_buf,   pcg_partial_mem);
        drop(pcg_reduction_buf, pcg_reduction_mem);
        std::fprintf(stderr, "[libpfsf] PCG buffer allocation failed for island %d\n", island_id);
        return false;
    }
    return true;
}

bool IslandBuffer::allocateMultigrid(VulkanContext& vk) {
    if (hasMultigridL1()) return true;
    if (!allocated || lx <= 0 || ly <= 0 || lz <= 0) return false;

    auto ceilDiv = [](int32_t a, int32_t b) -> int32_t { return (a + b - 1) / b; };

    lx_l1 = ceilDiv(lx, 2);
    ly_l1 = ceilDiv(ly, 2);
    lz_l1 = ceilDiv(lz, 2);

    // Java PFSFMultigridBuffers.allocate always allocates L2 at half of
    // L1 — we do the same; the dispatcher chooses whether to use L2 at
    // recording time based on whether the shortest L2 dim is meaningful.
    lx_l2 = ceilDiv(lx_l1, 2);
    ly_l2 = ceilDiv(ly_l1, 2);
    lz_l2 = ceilDiv(lz_l1, 2);

    const int64_t N1 = nL1();
    const int64_t N2 = nL2();
    if (N1 <= 0) return false;

    constexpr VkBufferUsageFlags MG_USAGE =
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
      | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
      | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    const VkDeviceSize f1   = static_cast<VkDeviceSize>(N1) * sizeof(float);
    const VkDeviceSize f1_6 = f1 * 6;
    const VkDeviceSize u1   = static_cast<VkDeviceSize>(N1);

    bool ok = true;
    ok &= vk.allocBuffer(f1,   MG_USAGE, &mg_phi_l1,      &mg_phi_l1_mem);
    ok &= vk.allocBuffer(f1,   MG_USAGE, &mg_phi_prev_l1, &mg_phi_prev_l1_mem);
    ok &= vk.allocBuffer(f1,   MG_USAGE, &mg_source_l1,   &mg_source_l1_mem);
    ok &= vk.allocBuffer(f1_6, MG_USAGE, &mg_cond_l1,     &mg_cond_l1_mem);
    ok &= vk.allocBuffer(u1,   MG_USAGE, &mg_type_l1,     &mg_type_l1_mem);

    if (ok && N2 > 0) {
        const VkDeviceSize f2   = static_cast<VkDeviceSize>(N2) * sizeof(float);
        const VkDeviceSize f2_6 = f2 * 6;
        const VkDeviceSize u2   = static_cast<VkDeviceSize>(N2);
        ok &= vk.allocBuffer(f2,   MG_USAGE, &mg_phi_l2,      &mg_phi_l2_mem);
        ok &= vk.allocBuffer(f2,   MG_USAGE, &mg_phi_prev_l2, &mg_phi_prev_l2_mem);
        ok &= vk.allocBuffer(f2,   MG_USAGE, &mg_source_l2,   &mg_source_l2_mem);
        ok &= vk.allocBuffer(f2_6, MG_USAGE, &mg_cond_l2,     &mg_cond_l2_mem);
        ok &= vk.allocBuffer(u2,   MG_USAGE, &mg_type_l2,     &mg_type_l2_mem);
    }

    if (!ok) {
        auto drop = [&](VkBuffer& b, VkDeviceMemory& m) {
            vk.freeBuffer(b, m); b = VK_NULL_HANDLE; m = VK_NULL_HANDLE;
        };
        drop(mg_phi_l1,      mg_phi_l1_mem);
        drop(mg_phi_prev_l1, mg_phi_prev_l1_mem);
        drop(mg_source_l1,   mg_source_l1_mem);
        drop(mg_cond_l1,     mg_cond_l1_mem);
        drop(mg_type_l1,     mg_type_l1_mem);
        drop(mg_phi_l2,      mg_phi_l2_mem);
        drop(mg_phi_prev_l2, mg_phi_prev_l2_mem);
        drop(mg_source_l2,   mg_source_l2_mem);
        drop(mg_cond_l2,     mg_cond_l2_mem);
        drop(mg_type_l2,     mg_type_l2_mem);
        lx_l1 = ly_l1 = lz_l1 = 0;
        lx_l2 = ly_l2 = lz_l2 = 0;
        std::fprintf(stderr, "[libpfsf] Multigrid allocation failed for island %d\n", island_id);
        return false;
    }
    return true;
}

bool IslandBuffer::allocateSparseUpload(VulkanContext& vk) {
    if (hasSparseUpload()) return true;

    const VkDeviceSize bytes = static_cast<VkDeviceSize>(MAX_SPARSE_UPDATES_PER_TICK)
                             * static_cast<VkDeviceSize>(SPARSE_RECORD_BYTES);

    VkBuffer buf = VK_NULL_HANDLE;
    void*    mapped = nullptr;
    if (!vk.allocHostVisibleStorage(bytes, &buf, &mapped) || buf == VK_NULL_HANDLE || mapped == nullptr) {
        std::fprintf(stderr, "[libpfsf] sparse_upload alloc failed (island %d, %lld B)\n",
                     island_id, static_cast<long long>(bytes));
        if (buf != VK_NULL_HANDLE) vk.freeBuffer(buf, VK_NULL_HANDLE);
        sparse_upload_buf       = VK_NULL_HANDLE;
        sparse_upload_mapped    = nullptr;
        sparse_upload_capacity  = 0;
        return false;
    }
    sparse_upload_buf       = buf;
    sparse_upload_mapped    = mapped;
    sparse_upload_capacity  = MAX_SPARSE_UPDATES_PER_TICK;
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

bool IslandBuffer::readbackPhi(VulkanContext& vk, float* out,
                                std::int32_t cap_floats, std::int32_t* out_count) {
    if (out_count) *out_count = 0;
    if (!allocated || out == nullptr || cap_floats <= 0) return false;

    VkBuffer phi = phi_flip ? phi_buf_b : phi_buf_a;
    if (phi == VK_NULL_HANDLE) return false;

    const std::int64_t n64 = N();
    const std::int32_t n_floats =
        static_cast<std::int32_t>(n64 < static_cast<std::int64_t>(cap_floats)
                                     ? n64 : static_cast<std::int64_t>(cap_floats));
    const VkDeviceSize bytes =
        static_cast<VkDeviceSize>(n_floats) * sizeof(float);
    if (bytes == 0) {
        if (out_count) *out_count = 0;
        return true;
    }

    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory unused = VK_NULL_HANDLE;
    if (!vk.allocBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        &staging, &unused)) {
        std::fprintf(stderr, "[libpfsf] readbackPhi: staging alloc failed (island %d, %lld B)\n",
                     island_id, static_cast<long long>(bytes));
        return false;
    }

    VkCommandBuffer cmd = vk.allocCmdBuffer();
    if (cmd == VK_NULL_HANDLE) {
        vk.freeBuffer(staging, VK_NULL_HANDLE);
        return false;
    }

    // COMPUTE_SHADER_WRITE → TRANSFER_READ — the last solver dispatch may
    // still be writing phi, so we wall off transfer from compute.
    VkMemoryBarrier pre{};
    pre.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    pre.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 1, &pre, 0, nullptr, 0, nullptr);

    VkBufferCopy region{};
    region.srcOffset = 0;
    region.dstOffset = 0;
    region.size      = bytes;
    vkCmdCopyBuffer(cmd, phi, staging, 1, &region);

    // TRANSFER_WRITE → HOST_READ so the host memcpy after submit sees it.
    VkMemoryBarrier post{};
    post.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0, 1, &post, 0, nullptr, 0, nullptr);

    vk.submitAndWait(cmd);

    void* mapped = vk.mapBuffer(staging, bytes);
    if (mapped == nullptr) {
        vk.freeBuffer(staging, VK_NULL_HANDLE);
        return false;
    }
    std::memcpy(out, mapped, bytes);
    vk.unmapBuffer(staging);
    vk.freeBuffer(staging, VK_NULL_HANDLE);

    if (out_count) *out_count = n_floats;
    return true;
}

bool IslandBuffer::readbackFailures(VulkanContext& vk, void* dbb_addr,
                                     std::int64_t dbb_bytes) {
    // Minimum usable DBB = 4 bytes header + 16 bytes (one x,y,z,type tuple).
    if (!allocated || dbb_addr == nullptr || dbb_bytes < 20) return false;
    if (fail_buf == VK_NULL_HANDLE) return false;

    const std::int64_t n64 = N();
    if (n64 <= 0) return true;
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(n64);  // fail_buf is 1 B/voxel

    VkBuffer staging       = VK_NULL_HANDLE;
    VkDeviceMemory unused  = VK_NULL_HANDLE;
    if (!vk.allocBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        &staging, &unused)) {
        std::fprintf(stderr, "[libpfsf] readbackFailures: staging alloc failed (island %d, %lld B)\n",
                     island_id, static_cast<long long>(bytes));
        return false;
    }

    VkCommandBuffer cmd = vk.allocCmdBuffer();
    if (cmd == VK_NULL_HANDLE) {
        vk.freeBuffer(staging, VK_NULL_HANDLE);
        return false;
    }

    // failure_scan writes fail_buf in compute; barrier to transfer-read.
    VkMemoryBarrier pre{};
    pre.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    pre.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 1, &pre, 0, nullptr, 0, nullptr);

    VkBufferCopy region{};
    region.size = bytes;
    vkCmdCopyBuffer(cmd, fail_buf, staging, 1, &region);

    VkMemoryBarrier post{};
    post.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0, 1, &post, 0, nullptr, 0, nullptr);

    vk.submitAndWait(cmd);

    const std::uint8_t* src =
        static_cast<const std::uint8_t*>(vk.mapBuffer(staging, bytes));
    if (src == nullptr) {
        vk.freeBuffer(staging, VK_NULL_HANDLE);
        return false;
    }

    // DBB is laid out as:
    //   [0] int32 count
    //   [4..] 4×int32 {x,y,z,type} tuples
    std::int32_t* header = static_cast<std::int32_t*>(dbb_addr);
    std::int32_t  count  = *header;
    const std::int64_t tupleBytes = 4 * sizeof(std::int32_t);
    const std::int32_t cap =
        static_cast<std::int32_t>((dbb_bytes - sizeof(std::int32_t)) / tupleBytes);
    std::int32_t* tuples =
        reinterpret_cast<std::int32_t*>(static_cast<std::uint8_t*>(dbb_addr)
                                         + sizeof(std::int32_t));

    for (std::int64_t idx = 0; idx < n64 && count < cap; ++idx) {
        std::uint8_t f = src[idx];
        if (f == 0) continue;  // PFSF_FAIL_OK — skip

        // Flat-index → (x, y, z) in grid-local coords, then shift by origin.
        std::int32_t ix = static_cast<std::int32_t>(idx % lx);
        std::int32_t iy = static_cast<std::int32_t>((idx / lx) % ly);
        std::int32_t iz = static_cast<std::int32_t>(idx / (static_cast<std::int64_t>(lx) * ly));

        std::int32_t* ev = &tuples[count * 4];
        ev[0] = origin.x + ix;
        ev[1] = origin.y + iy;
        ev[2] = origin.z + iz;
        ev[3] = static_cast<std::int32_t>(f);
        ++count;
    }
    *header = count;

    vk.unmapBuffer(staging);
    vk.freeBuffer(staging, VK_NULL_HANDLE);
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
    freeOne(pcg_r_buf,         pcg_r_mem);
    freeOne(pcg_z_buf,         pcg_z_mem);
    freeOne(pcg_p_buf,         pcg_p_mem);
    freeOne(pcg_ap_buf,        pcg_ap_mem);
    freeOne(pcg_partial_buf,   pcg_partial_mem);
    freeOne(pcg_reduction_buf, pcg_reduction_mem);
    freeOne(mg_phi_l1,         mg_phi_l1_mem);
    freeOne(mg_phi_prev_l1,    mg_phi_prev_l1_mem);
    freeOne(mg_source_l1,      mg_source_l1_mem);
    freeOne(mg_cond_l1,        mg_cond_l1_mem);
    freeOne(mg_type_l1,        mg_type_l1_mem);
    freeOne(mg_phi_l2,         mg_phi_l2_mem);
    freeOne(mg_phi_prev_l2,    mg_phi_prev_l2_mem);
    freeOne(mg_source_l2,      mg_source_l2_mem);
    freeOne(mg_cond_l2,        mg_cond_l2_mem);
    freeOne(mg_type_l2,        mg_type_l2_mem);
    lx_l1 = ly_l1 = lz_l1 = 0;
    lx_l2 = ly_l2 = lz_l2 = 0;

    // Sparse-update upload buffer — VMA owns the persistent mapping; a
    // plain freeBuffer() call tears both the buffer and the mapping down.
    if (sparse_upload_buf != VK_NULL_HANDLE) {
        vk.freeBuffer(sparse_upload_buf, VK_NULL_HANDLE);
        sparse_upload_buf      = VK_NULL_HANDLE;
        sparse_upload_mapped   = nullptr;
        sparse_upload_capacity = 0;
    }

    freeOne(staging_buf,      staging_mem);

    allocated = false;
}

} // namespace pfsf
