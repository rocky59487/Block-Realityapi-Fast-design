/**
 * @file descriptor_cache.h
 * @brief LRU descriptor-set cache — eliminates per-tick
 *        vkAllocateDescriptorSets cost.
 *
 * Key = (pipeline_layout_handle, hash(bound_buffer_handles)). Target
 * hit-rate > 98 % on static bindings; see plan §D.3.
 */
#ifndef BR_CORE_DESCRIPTOR_CACHE_H
#define BR_CORE_DESCRIPTOR_CACHE_H

#include <vulkan/vulkan.h>

#include <cstdint>
#include <vector>

namespace br_core {

struct DescriptorBinding {
    std::uint32_t      binding = 0;
    VkDescriptorType   type    = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    VkBuffer           buffer  = VK_NULL_HANDLE;
    VkDeviceSize       offset  = 0;
    VkDeviceSize       range   = VK_WHOLE_SIZE;
    VkImageView        image_view = VK_NULL_HANDLE;   ///< for image/storage-image types
    VkSampler          sampler    = VK_NULL_HANDLE;
    std::uint32_t      count   = 1;   // array size; 1 for plain SSBO
    VkShaderStageFlags stages  = VK_SHADER_STAGE_COMPUTE_BIT;
};

struct DescriptorKey {
    VkDescriptorSetLayout layout = VK_NULL_HANDLE;
    std::uint64_t         bindings_hash = 0;

    bool operator==(const DescriptorKey& o) const {
        return layout == o.layout && bindings_hash == o.bindings_hash;
    }
};

class DescriptorCache {
public:
    DescriptorCache() = default;
    ~DescriptorCache();

    DescriptorCache(const DescriptorCache&)            = delete;
    DescriptorCache& operator=(const DescriptorCache&) = delete;

    bool init(VkDevice device, std::uint32_t capacity = 1024);
    void shutdown();

    /**
     * Fetch or allocate a descriptor set matching (layout, bindings).
     * Returns VK_NULL_HANDLE on pool exhaustion — callers should
     * either evict some entries or fall back to transient allocation.
     */
    VkDescriptorSet acquire(VkDescriptorSetLayout layout,
                             const DescriptorBinding* bindings,
                             std::uint32_t binding_count);

    /** Clear all cached sets. Reset the pool. */
    void reset();

    // Telemetry — wired to Java stats query.
    std::uint64_t hits()   const { return hits_; }
    std::uint64_t misses() const { return misses_; }

private:
    static std::uint64_t hash_bindings(const DescriptorBinding* b, std::uint32_t n);

    VkDevice         device_  = VK_NULL_HANDLE;
    VkDescriptorPool pool_    = VK_NULL_HANDLE;
    std::uint32_t    capacity_ = 0;
    std::uint64_t    hits_    = 0;
    std::uint64_t    misses_  = 0;
    // Flat LRU table. Linear scan is fine up to ~1024 entries; profile
    // later if this ever becomes hot (it won't — we want > 98 % hits).
    struct Entry {
        DescriptorKey   key{};
        VkDescriptorSet set = VK_NULL_HANDLE;
        std::uint64_t   last_use_epoch = 0;
    };
    std::vector<Entry> table_;
    std::uint64_t      tick_epoch_ = 0;
};

} // namespace br_core

#endif // BR_CORE_DESCRIPTOR_CACHE_H
