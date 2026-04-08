/**
 * @file buffer_manager.h
 * @brief Manages island buffers — create, lookup, evict.
 *
 * Mirrors Java PFSFBufferManager.
 */
#pragma once

#include "island_buffer.h"
#include <unordered_map>
#include <cstdint>

namespace pfsf {

class VulkanContext;

class BufferManager {
public:
    explicit BufferManager(VulkanContext& vk, bool enable_phase_field = true);
    ~BufferManager();

    BufferManager(const BufferManager&) = delete;
    BufferManager& operator=(const BufferManager&) = delete;

    /**
     * Get or create an island buffer.
     *
     * @return Pointer to existing/new buffer, or nullptr on VRAM failure.
     */
    IslandBuffer* getOrCreate(const pfsf_island_desc& desc);

    /** Look up an existing buffer (nullptr if not found). */
    IslandBuffer* get(int32_t island_id);

    /** Remove and free an island buffer. */
    void remove(int32_t island_id);

    /** Free all island buffers. */
    void freeAll();

    /** Number of active islands. */
    int32_t count() const { return static_cast<int32_t>(buffers_.size()); }

    /** Total voxels across all islands. */
    int64_t totalVoxels() const;

    /** Iterator access for tick loop. */
    auto begin() { return buffers_.begin(); }
    auto end()   { return buffers_.end(); }

private:
    VulkanContext& vk_;
    bool           phase_field_;
    std::unordered_map<int32_t, IslandBuffer> buffers_;
};

} // namespace pfsf
