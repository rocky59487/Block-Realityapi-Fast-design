/**
 * @file buffer_manager.cpp
 * @brief Island buffer lifecycle management.
 */
#include "buffer_manager.h"
#include "vulkan_context.h"

namespace pfsf {

BufferManager::BufferManager(VulkanContext& vk, bool enable_phase_field)
    : vk_(vk), phase_field_(enable_phase_field) {}

BufferManager::~BufferManager() {
    freeAll();
}

IslandBuffer* BufferManager::getOrCreate(const pfsf_island_desc& desc) {
    auto it = buffers_.find(desc.island_id);
    if (it != buffers_.end()) {
        IslandBuffer* existing = it->second.get();
        if (existing->lx == desc.lx && existing->ly == desc.ly && existing->lz == desc.lz) {
            return existing;
        }
        // Dimensions changed — attempt new allocation FIRST before freeing old buffer
        auto newBuf = std::make_unique<IslandBuffer>();
        newBuf->island_id = desc.island_id;
        newBuf->origin    = desc.origin;
        newBuf->lx        = desc.lx;
        newBuf->ly        = desc.ly;
        newBuf->lz        = desc.lz;

        if (!newBuf->allocate(vk_, phase_field_)) {
            // New allocation failed — keep the old buffer intact and return nullptr
            return nullptr;
        }

        // New allocation succeeded — now it's safe to free the old buffer
        existing->free(vk_);
        IslandBuffer* raw = newBuf.get();
        it->second = std::move(newBuf);  // replace in-place, no erase needed
        return raw;
    }

    // No existing entry — standard allocation path
    auto buf = std::make_unique<IslandBuffer>();
    buf->island_id = desc.island_id;
    buf->origin    = desc.origin;
    buf->lx        = desc.lx;
    buf->ly        = desc.ly;
    buf->lz        = desc.lz;

    if (!buf->allocate(vk_, phase_field_)) {
        return nullptr;
    }

    IslandBuffer* raw = buf.get();
    buffers_.emplace(desc.island_id, std::move(buf));
    return raw;
}

IslandBuffer* BufferManager::get(int32_t island_id) {
    auto it = buffers_.find(island_id);
    return it != buffers_.end() ? it->second.get() : nullptr;
}

void BufferManager::remove(int32_t island_id) {
    auto it = buffers_.find(island_id);
    if (it != buffers_.end()) {
        it->second->free(vk_);
        buffers_.erase(it);
    }
}

void BufferManager::freeAll() {
    for (auto& [id, buf] : buffers_) {
        buf->free(vk_);
    }
    buffers_.clear();
}

int64_t BufferManager::totalVoxels() const {
    int64_t total = 0;
    for (auto& [id, buf] : buffers_) {
        total += buf->N();
    }
    return total;
}

} // namespace pfsf
