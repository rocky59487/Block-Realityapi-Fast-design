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

    // ── Multigrid coarse-grid state (V-Cycle / W-Cycle) ──
    // Mirrors Java PFSFMultigridBuffers. Populated by allocateMultigrid().
    // The restrict shader reads the fine-grid phi/source/cond/type and
    // writes phi/source at the next level; cond_l1/type_l1 are populated
    // separately (Java: PFSFIslandBuffer.uploadCoarseData).
    //
    //   L1 = fine / 2   (V-Cycle only needs L1)
    //   L2 = L1   / 2   (W-Cycle adds L2 for the recursive second visit)
    int32_t  lx_l1 = 0, ly_l1 = 0, lz_l1 = 0;
    int32_t  lx_l2 = 0, ly_l2 = 0, lz_l2 = 0;

    VkBuffer mg_phi_l1      = VK_NULL_HANDLE; VkDeviceMemory mg_phi_l1_mem      = VK_NULL_HANDLE;
    VkBuffer mg_phi_prev_l1 = VK_NULL_HANDLE; VkDeviceMemory mg_phi_prev_l1_mem = VK_NULL_HANDLE;
    VkBuffer mg_source_l1   = VK_NULL_HANDLE; VkDeviceMemory mg_source_l1_mem   = VK_NULL_HANDLE;
    VkBuffer mg_cond_l1     = VK_NULL_HANDLE; VkDeviceMemory mg_cond_l1_mem     = VK_NULL_HANDLE;
    VkBuffer mg_type_l1     = VK_NULL_HANDLE; VkDeviceMemory mg_type_l1_mem     = VK_NULL_HANDLE;

    VkBuffer mg_phi_l2      = VK_NULL_HANDLE; VkDeviceMemory mg_phi_l2_mem      = VK_NULL_HANDLE;
    VkBuffer mg_phi_prev_l2 = VK_NULL_HANDLE; VkDeviceMemory mg_phi_prev_l2_mem = VK_NULL_HANDLE;
    VkBuffer mg_source_l2   = VK_NULL_HANDLE; VkDeviceMemory mg_source_l2_mem   = VK_NULL_HANDLE;
    VkBuffer mg_cond_l2     = VK_NULL_HANDLE; VkDeviceMemory mg_cond_l2_mem     = VK_NULL_HANDLE;
    VkBuffer mg_type_l2     = VK_NULL_HANDLE; VkDeviceMemory mg_type_l2_mem     = VK_NULL_HANDLE;

    bool hasMultigridL1() const {
        return mg_phi_l1    != VK_NULL_HANDLE
            && mg_source_l1 != VK_NULL_HANDLE
            && mg_cond_l1   != VK_NULL_HANDLE
            && mg_type_l1   != VK_NULL_HANDLE;
    }
    bool hasMultigridL2() const {
        return mg_phi_l2    != VK_NULL_HANDLE
            && mg_source_l2 != VK_NULL_HANDLE
            && mg_cond_l2   != VK_NULL_HANDLE
            && mg_type_l2   != VK_NULL_HANDLE;
    }
    int64_t nL1() const { return static_cast<int64_t>(lx_l1) * ly_l1 * lz_l1; }
    int64_t nL2() const { return static_cast<int64_t>(lx_l2) * ly_l2 * lz_l2; }

    // ── Sparse voxel update (tick-time re-upload) ──
    // Mirrors Java PFSFSparseUpdate — a persistent-mapped host-visible
    // SSBO that holds up to MAX_SPARSE_UPDATES_PER_TICK packed
    // VoxelUpdate records. Each record is SPARSE_RECORD_BYTES (= 48 B):
    //   index(4) + source(4) + type(4) + maxPhi(4) + rcomp(4) + rtens(4) + cond×6(24).
    // The CPU writes the deltas each tick; sparse_scatter.comp reads this
    // SSBO and scatters them into the large device-local arrays (185,000×
    // PCIe bandwidth saving vs. full re-upload).
    static constexpr std::int32_t MAX_SPARSE_UPDATES_PER_TICK = 512;
    static constexpr std::int32_t SPARSE_RECORD_BYTES         = 48;

    VkBuffer sparse_upload_buf    = VK_NULL_HANDLE;
    void*    sparse_upload_mapped = nullptr;   ///< persistent host pointer (VMA-owned)
    std::int32_t sparse_upload_capacity = 0;   ///< in records, not bytes

    bool hasSparseUpload() const {
        return sparse_upload_buf != VK_NULL_HANDLE && sparse_upload_mapped != nullptr;
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

    // ── Residual-driven RBGS→PCG adaptive switch state (M2o) ──
    // Mirrors Java PFSFIslandBuffer.prevMaxMacroResidual /
    // cachedMacroResiduals. Both values hold the max of macro-block
    // residuals at the TOP of the corresponding tick — the dispatcher
    // compares last/prev to decide whether RBGS has stagnated.
    float    prev_max_macro_residual = 0.0f;  ///< two ticks ago
    float    last_max_macro_residual = 0.0f;  ///< most recent tick

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

    /** Allocate L1 (V-Cycle) + L2 (W-Cycle) coarse-grid buffers.
     *  Idempotent — noop if already allocated. L2 is skipped when the
     *  L1 shortest side is already ≤ 2 (no meaningful deeper coarsening
     *  — mirrors Java PFSFMultigridBuffers.allocate). */
    bool allocateMultigrid(VulkanContext& vk);

    /** Allocate the persistent-mapped host-visible sparse-update upload
     *  SSBO (MAX_SPARSE_UPDATES_PER_TICK × SPARSE_RECORD_BYTES). Idempotent
     *  — noop if already allocated. Mirrors
     *  PFSFSparseUpdate.allocateUploadBuffer on the Java side. */
    bool allocateSparseUpload(VulkanContext& vk);

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

    /** Number of macro blocks along each axis (8×8×8 cells per block). */
    std::int32_t numMacroBlocks() const {
        constexpr std::int32_t MB = 8;
        const std::int32_t mbx = (lx + MB - 1) / MB;
        const std::int32_t mby = (ly + MB - 1) / MB;
        const std::int32_t mbz = (lz + MB - 1) / MB;
        return mbx * mby * mbz;
    }

    /** Zero-fill the macro_residual buffer via vkCmdFillBuffer recorded into
     *  @p cmd. Must be called before the first failure_scan of the tick so
     *  its atomicMax accumulates a per-tick (not ever-seen) value. */
    void recordClearMacroResiduals(VkCommandBuffer cmd);

    /** Synchronous max-reduce of the macro_residual buffer. Interprets each
     *  uint32 entry as a float (matches rbgs_smooth's uintBitsToFloat) and
     *  returns the largest non-negative value. Returns 0 on any allocation
     *  or transfer failure. Only numMacroBlocks() entries are examined. */
    float readbackMacroResidualMax(VulkanContext& vk);

    /** Free all GPU buffers. */
    void free(VulkanContext& vk);
};

} // namespace pfsf
