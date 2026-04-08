/**
 * @file island_buffer.cpp
 * @brief GPU buffer allocation/deallocation for a structure island.
 */
#include "island_buffer.h"
#include "vulkan_context.h"
#include <cstdio>

namespace pfsf {

static constexpr VkBufferUsageFlags STORAGE =
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

static constexpr VkBufferUsageFlags STAGING =
    VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

bool IslandBuffer::allocate(VulkanContext& vk, bool with_phase_field) {
    if (allocated) return true;
    int32_t n = N();
    if (n <= 0) return false;

    VkDeviceSize f32n  = static_cast<VkDeviceSize>(n) * sizeof(float);
    VkDeviceSize f32_6n = f32n * 6;   // conductivity SoA
    VkDeviceSize u8n   = static_cast<VkDeviceSize>(n);

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
                island_id, n);
        free(vk);
        return false;
    }

    allocated = true;
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
    freeOne(h_field_buf,   h_field_mem);
    freeOne(d_field_buf,   d_field_mem);
    freeOne(hydration_buf, hydration_mem);
    freeOne(staging_buf,   staging_mem);

    allocated = false;
}

} // namespace pfsf
