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

namespace pfsf {

class VulkanContext;

struct IslandBuffer {
    // ── Grid dimensions ──
    int32_t  island_id = -1;
    pfsf_pos origin{};
    int32_t  lx = 0, ly = 0, lz = 0;

    int64_t N() const { return static_cast<int64_t>(lx) * ly * lz; }

    int64_t flatIndex(int32_t x, int32_t y, int32_t z) const {
        int64_t ix = static_cast<int64_t>(x) - origin.x;
        int64_t iy = static_cast<int64_t>(y) - origin.y;
        int64_t iz = static_cast<int64_t>(z) - origin.z;
        if (ix < 0 || ix >= lx || iy < 0 || iy >= ly || iz < 0 || iz >= lz) return -1;
        return ix + static_cast<int64_t>(lx) * (iy + static_cast<int64_t>(ly) * iz);
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

    // Macro-block residual bits — written by RBGS (binding 5) and by
    // failure_scan (binding 7). Must be a dedicated buffer; aliasing onto
    // fail_buf corrupts per-voxel failure codes.
    VkBuffer macro_residual_buf = VK_NULL_HANDLE; VkDeviceMemory macro_residual_mem = VK_NULL_HANDLE;

    // ── PCG state (Jacobi-preconditioned CG) ──
    // Allocated on demand when PCG Phase-2 is enabled. Dispatcher checks
    // hasPCGBuffers() before routing through the PCG tail.
    //
    // Layout mirrors Java PFSFIslandBuffer.allocatePCG():
    //   pcg_r_buf / pcg_p_buf / pcg_ap_buf  — N floats each
    //   pcg_z_buf                           — N floats (retained for
    //                                          parity; Jacobi z is re-derived
    //                                          inside pcg_update/direction)
    //   pcg_partial_buf                     — numGroups floats (per-WG
    //                                          scratch for dot pass 1)
    //   pcg_reduction_buf                   — PCG_REDUCTION_SLOTS floats
    //                                          (scalar outputs: rTz_old,
    //                                          pAp, rTz_new, spare)
    VkBuffer pcg_r_buf         = VK_NULL_HANDLE; VkDeviceMemory pcg_r_mem         = VK_NULL_HANDLE;
    VkBuffer pcg_z_buf         = VK_NULL_HANDLE; VkDeviceMemory pcg_z_mem         = VK_NULL_HANDLE;
    VkBuffer pcg_p_buf         = VK_NULL_HANDLE; VkDeviceMemory pcg_p_mem         = VK_NULL_HANDLE;
    VkBuffer pcg_ap_buf        = VK_NULL_HANDLE; VkDeviceMemory pcg_ap_mem        = VK_NULL_HANDLE;
    VkBuffer pcg_partial_buf   = VK_NULL_HANDLE; VkDeviceMemory pcg_partial_mem   = VK_NULL_HANDLE;
    VkBuffer pcg_reduction_buf = VK_NULL_HANDLE; VkDeviceMemory pcg_reduction_mem = VK_NULL_HANDLE;

    bool hasPCGBuffers() const {
        return pcg_r_buf         != VK_NULL_HANDLE
            && pcg_z_buf         != VK_NULL_HANDLE
            && pcg_p_buf         != VK_NULL_HANDLE
            && pcg_ap_buf        != VK_NULL_HANDLE
            && pcg_partial_buf   != VK_NULL_HANDLE
            && pcg_reduction_buf != VK_NULL_HANDLE;
    }

    // Staging (CPU↔GPU transfer)
    VkBuffer staging_buf   = VK_NULL_HANDLE; VkDeviceMemory staging_mem   = VK_NULL_HANDLE;

    // ── Solver state ──
    bool     dirty           = true;
    bool     allocated       = false;
    int      chebyshev_iter  = 0;
    float    max_phi_prev    = 0.0f;
    float    max_phi_prev2   = 0.0f;
    bool     damping_active  = false;

    // ── Host-pointer cache for DBB zero-copy registration ──
    // Filled by pfsf_register_island_buffers. Valid for the island
    // lifetime; Java owns the backing DirectByteBuffer memory.
    struct HostAddrs {
        const void* phi          = nullptr; std::int64_t phi_bytes          = 0;
        const void* source       = nullptr; std::int64_t source_bytes       = 0;
        const void* conductivity = nullptr; std::int64_t conductivity_bytes = 0;
        const void* voxel_type   = nullptr; std::int64_t voxel_type_bytes   = 0;
        const void* rcomp        = nullptr; std::int64_t rcomp_bytes        = 0;
        const void* rtens        = nullptr; std::int64_t rtens_bytes        = 0;
        bool registered          = false;

        // World-state lookup DBBs — Java refreshes only dirty ranges each
        // tick (PFSFDataBuilder), C++ reads them without per-voxel JNI.
        // Written via pfsf_register_island_lookups; valid for island life.
        const void*  material_id     = nullptr; std::int64_t material_id_bytes     = 0;
        const void*  anchor_bitmap   = nullptr; std::int64_t anchor_bitmap_bytes   = 0;
        const void*  fluid_pressure  = nullptr; std::int64_t fluid_pressure_bytes  = 0;
        const void*  curing          = nullptr; std::int64_t curing_bytes          = 0;
        bool         lookups_registered = false;

        // Stress readback DBB — written back to after each tick when
        // registered. Host owns the memory; lifetime matches the island.
        void*        stress_out  = nullptr;
        std::int64_t stress_bytes = 0;
    } hosts;

    // ── Reference counting (async safety) ──

    void markDirty()  { dirty = true; }
    void markClean()  { dirty = false; }

    // ── Lifecycle ──

    /** Allocate all GPU buffers for this island. */
    bool allocate(VulkanContext& vk, bool with_phase_field);

    /** Allocate PCG state buffers (r/z/p/Ap/partialSums). Idempotent —
     *  noop if already allocated. Returns true on success. */
    bool allocatePCG(VulkanContext& vk);

    /**
     * Upload the six registered host fields (phi, source, conductivity,
     * voxel_type, rcomp, rtens) into the device-local SSBOs. Synchronous:
     * allocates temporary staging buffers, memcpy's each field, records a
     * vkCmdCopyBuffer chain, and submit-waits before returning. Safe only
     * after hosts.registered == true. Returns true on success.
     */
    bool uploadFromHosts(VulkanContext& vk);

    /**
     * Read back the current phi field (whichever side of the flip is
     * active) into @p out. Synchronous: device → staging → host memcpy.
     *
     * @param cap_floats maximum number of floats the caller's buffer holds.
     * @param out_count  number of floats actually written (≤ cap_floats, ≤ N).
     * @return true on success.
     */
    bool readbackPhi(VulkanContext& vk, float* out, std::int32_t cap_floats,
                     std::int32_t* out_count);

    /**
     * Scan the per-voxel @c fail_buf and append every non-zero code into
     * @p failure_dbb, which follows the NativePFSFBridge wire format:
     *
     *     int32_t header_count;                // at offset 0 — updated
     *     struct { int32 x,y,z,type; } events; // packed, [1..header_count]
     *
     * Synchronous (device → staging → host). Multi-island-safe: the
     * caller may invoke this repeatedly for different islands — the
     * header is atomically bumped and new events are appended after the
     * previously-written ones until capacity is exhausted.
     *
     * @param dbb_addr   direct pointer to the shared failure DBB.
     * @param dbb_bytes  capacity of @p dbb_addr in bytes.
     * @return true on success (no GPU/transfer failure).
     */
    bool readbackFailures(VulkanContext& vk, void* dbb_addr, std::int64_t dbb_bytes);

    /** Free all GPU buffers. */
    void free(VulkanContext& vk);
};

} // namespace pfsf
