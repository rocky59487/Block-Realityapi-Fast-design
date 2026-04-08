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
    if (it != buffers_.end()) return &it->second;

    IslandBuffer buf;
    buf.island_id = desc.island_id;
    buf.origin    = desc.origin;
    buf.lx        = desc.lx;
    buf.ly        = desc.ly;
    buf.lz        = desc.lz;

    if (!buf.allocate(vk_, phase_field_)) {
        return nullptr;
    }

    auto [inserted, ok] = buffers_.emplace(desc.island_id, std::move(buf));
    return ok ? &inserted->second : nullptr;
}

IslandBuffer* BufferManager::get(int32_t island_id) {
    auto it = buffers_.find(island_id);
    return it != buffers_.end() ? &it->second : nullptr;
}

void BufferManager::remove(int32_t island_id) {
    auto it = buffers_.find(island_id);
    if (it != buffers_.end()) {
        it->second.free(vk_);
        buffers_.erase(it);
    }
}

void BufferManager::freeAll() {
    for (auto& [id, buf] : buffers_) {
        buf.free(vk_);
    }
    buffers_.clear();
}

int64_t BufferManager::totalVoxels() const {
    int64_t total = 0;
    for (auto& [id, buf] : buffers_) {
        total += buf.N();
    }
    return total;
}

} // namespace pfsf
