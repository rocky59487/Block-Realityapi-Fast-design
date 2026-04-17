/**
 * @file sparse_scatter.h
 * @brief Sparse voxel-update scatter — mirrors
 *        Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/
 *        PFSFSparseUpdate + PFSFFailureRecorder.recordSparseScatter.
 *
 * Per tick, Java packs up to MAX_SPARSE_UPDATES_PER_TICK (= 512) 48-byte
 * VoxelUpdate records into a persistent-mapped host-visible SSBO and
 * dispatches sparse_scatter.comp to write the deltas into the large
 * device-local source/conductivity/type/maxPhi/rcomp/rtens arrays. That
 * avoids re-uploading the full 37 MB set every time a block is placed or
 * broken (~185,000× bandwidth saving).
 *
 * This header owns only the pipeline wrapper; the per-island upload
 * buffer + mapping live on IslandBuffer (allocateSparseUpload). The
 * dispatcher chooses whether to record a sparse or full rebuild each
 * tick — same decision the Java PFSFDispatcher.handleDataUpload makes.
 */
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include "br_core/compute_pipeline.h"

namespace pfsf {

class VulkanContext;
struct IslandBuffer;

/** Push constants for sparse_scatter.comp.glsl (8 bytes total). */
struct SparseScatterPushConstants {
    std::uint32_t updateCount;   // number of packed VoxelUpdate records
    std::uint32_t totalN;        // island voxel count (needed for SoA stride)
};
static_assert(sizeof(SparseScatterPushConstants) == 8,
              "SparseScatterPushConstants must be 8 bytes to match sparse_scatter.comp.glsl");

class SparseScatterSolver {
public:
    explicit SparseScatterSolver(VulkanContext& vk);
    ~SparseScatterSolver();

    bool createPipeline();
    void destroyPipeline();

    bool isReady() const { return pipeline_.pipeline != VK_NULL_HANDLE; }

    /**
     * Record a sparse-scatter dispatch for @p updateCount records already
     * packed into @c buf.sparse_upload_buf by the caller. Binds the seven
     * storage buffers (upload, source, cond, type, maxPhi, rcomp, rtens)
     * in the same order as PFSFFailureRecorder.recordSparseScatter.
     *
     * No-ops if the pipeline isn't ready, updateCount is zero, or the
     * island lacks any required buffer handle (including sparse_upload_buf).
     * The caller must have written ≥ updateCount * 48 bytes into
     * sparse_upload_mapped before submitting the recorded command buffer.
     */
    void recordScatter(VkCommandBuffer cmd, IslandBuffer& buf,
                       VkDescriptorPool pool, std::uint32_t updateCount);

private:
    VulkanContext&            vk_;
    br_core::ComputePipeline  pipeline_{};
};

} // namespace pfsf
