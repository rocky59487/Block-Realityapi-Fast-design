/**
 * @file island_buffer.h
 * @brief GPU buffer set for a single structure island.
 *
 * Mirrors Java PFSFIslandBuffer — flat 3D array layout:
 *   flat_index = x + Lx * (y + Ly * z)
 *
 * SoA conductivity layout:
 *   conductivity[dir * N + flat_index]  (dir ∈ [0,5])
 */
#pragma once

#include <vulkan/vulkan.h>
#include <pfsf/pfsf_types.h>
#include <cstdint>
#include <atomic>

namespace pfsf {

class VulkanContext;

struct IslandBuffer {
    // ── Grid dimensions ──
    int32_t  island_id = -1;
    pfsf_pos origin{};
    int32_t  lx = 0, ly = 0, lz = 0;

    int32_t N() const { return lx * ly * lz; }

    int32_t flatIndex(int32_t x, int32_t y, int32_t z) const {
        return (x - origin.x) + lx * ((y - origin.y) + ly * (z - origin.z));
    }

    // ── GPU buffer handles (buffer + memory pairs) ──

    // Phi flip buffers (Chebyshev)
    VkBuffer phi_buf_a     = VK_NULL_HANDLE; VkDeviceMemory phi_mem_a     = VK_NULL_HANDLE;
    VkBuffer phi_buf_b     = VK_NULL_HANDLE; VkDeviceMemory phi_mem_b     = VK_NULL_HANDLE;
    bool     phi_flip      = false;   // false → A is current, true → B is current

    // Source (self-weight)
    VkBuffer source_buf    = VK_NULL_HANDLE; VkDeviceMemory source_mem    = VK_NULL_HANDLE;

    // Conductivity (6N floats, SoA)
    VkBuffer cond_buf      = VK_NULL_HANDLE; VkDeviceMemory cond_mem      = VK_NULL_HANDLE;

    // Type (N bytes)
    VkBuffer type_buf      = VK_NULL_HANDLE; VkDeviceMemory type_mem      = VK_NULL_HANDLE;

    // Failure flags (N bytes)
    VkBuffer fail_buf      = VK_NULL_HANDLE; VkDeviceMemory fail_mem      = VK_NULL_HANDLE;

    // MaxPhi (per-voxel limit)
    VkBuffer max_phi_buf   = VK_NULL_HANDLE; VkDeviceMemory max_phi_mem   = VK_NULL_HANDLE;

    // Rcomp (compression strength)
    VkBuffer rcomp_buf     = VK_NULL_HANDLE; VkDeviceMemory rcomp_mem     = VK_NULL_HANDLE;

    // Rtens (tension strength)
    VkBuffer rtens_buf     = VK_NULL_HANDLE; VkDeviceMemory rtens_mem     = VK_NULL_HANDLE;

    // Phase-field fracture
    VkBuffer h_field_buf   = VK_NULL_HANDLE; VkDeviceMemory h_field_mem   = VK_NULL_HANDLE;
    VkBuffer d_field_buf   = VK_NULL_HANDLE; VkDeviceMemory d_field_mem   = VK_NULL_HANDLE;

    // Hydration (curing)
    VkBuffer hydration_buf = VK_NULL_HANDLE; VkDeviceMemory hydration_mem = VK_NULL_HANDLE;

    // Staging (CPU↔GPU transfer)
    VkBuffer staging_buf   = VK_NULL_HANDLE; VkDeviceMemory staging_mem   = VK_NULL_HANDLE;

    // ── Solver state ──
    bool     dirty           = true;
    bool     allocated       = false;
    int      chebyshev_iter  = 0;
    float    max_phi_prev    = 0.0f;
    float    max_phi_prev2   = 0.0f;
    bool     damping_active  = false;

    // ── Reference counting (async safety) ──
    std::atomic<int> ref_count{0};

    void retain()  { ref_count.fetch_add(1, std::memory_order_relaxed); }
    bool release() { return ref_count.fetch_sub(1, std::memory_order_acq_rel) == 1; }

    void markDirty()  { dirty = true; }
    void markClean()  { dirty = false; }

    // ── Lifecycle ──

    /** Allocate all GPU buffers for this island. */
    bool allocate(VulkanContext& vk, bool with_phase_field);

    /** Free all GPU buffers. */
    void free(VulkanContext& vk);
};

} // namespace pfsf
