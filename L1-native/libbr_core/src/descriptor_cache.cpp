/**
 * @file descriptor_cache.cpp
 * @brief LRU descriptor-set cache implementation.
 */
#include "br_core/descriptor_cache.h"

#include <algorithm>
#include <cstdint>
#include <cstring>

namespace br_core {

namespace {

// FNV-1a 64-bit — cheap and good enough for descriptor-binding tuples.
constexpr std::uint64_t kFnvOffset = 0xcbf29ce484222325ULL;
constexpr std::uint64_t kFnvPrime  = 0x100000001b3ULL;

void fnv_absorb(std::uint64_t& h, const void* data, std::size_t n) {
    const std::uint8_t* p = static_cast<const std::uint8_t*>(data);
    for (std::size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= kFnvPrime;
    }
}

} // namespace

DescriptorCache::~DescriptorCache() {
    shutdown();
}

std::uint64_t DescriptorCache::hash_bindings(const DescriptorBinding* b, std::uint32_t n) {
    std::uint64_t h = kFnvOffset;
    fnv_absorb(h, &n, sizeof(n));
    for (std::uint32_t i = 0; i < n; ++i) {
        fnv_absorb(h, &b[i].binding, sizeof(b[i].binding));
        fnv_absorb(h, &b[i].type,    sizeof(b[i].type));
        fnv_absorb(h, &b[i].buffer,  sizeof(b[i].buffer));
        fnv_absorb(h, &b[i].offset,  sizeof(b[i].offset));
        fnv_absorb(h, &b[i].range,   sizeof(b[i].range));
        fnv_absorb(h, &b[i].image_view, sizeof(b[i].image_view));
        fnv_absorb(h, &b[i].sampler,    sizeof(b[i].sampler));
    }
    return h;
}

bool DescriptorCache::init(VkDevice device, std::uint32_t capacity) {
    if (pool_ != VK_NULL_HANDLE) return true;
    device_   = device;
    capacity_ = capacity;

    // Overprovisioned per-type pool, sized for typical domain workloads.
    VkDescriptorPoolSize sizes[] = {
        { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,         capacity * 16 },
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,         capacity * 4  },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          capacity * 4  },
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, capacity * 4  },
    };
    VkDescriptorPoolCreateInfo pci{};
    pci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pci.flags         = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    pci.maxSets       = capacity;
    pci.poolSizeCount = static_cast<std::uint32_t>(sizeof(sizes) / sizeof(sizes[0]));
    pci.pPoolSizes    = sizes;

    if (vkCreateDescriptorPool(device_, &pci, nullptr, &pool_) != VK_SUCCESS) {
        pool_ = VK_NULL_HANDLE;
        return false;
    }
    table_.reserve(capacity);
    return true;
}

void DescriptorCache::shutdown() {
    if (pool_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, pool_, nullptr);
    }
    pool_   = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    table_.clear();
    hits_ = misses_ = 0;
}

VkDescriptorSet DescriptorCache::acquire(VkDescriptorSetLayout layout,
                                          const DescriptorBinding* bindings,
                                          std::uint32_t binding_count) {
    if (pool_ == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    ++tick_epoch_;

    DescriptorKey key{ layout, hash_bindings(bindings, binding_count) };

    for (auto& e : table_) {
        if (e.key == key && e.set != VK_NULL_HANDLE) {
            e.last_use_epoch = tick_epoch_;
            ++hits_;
            return e.set;
        }
    }
    ++misses_;

    // Evict the least-recently-used entry if at capacity.
    if (table_.size() >= capacity_) {
        auto victim = std::min_element(table_.begin(), table_.end(),
            [](const Entry& a, const Entry& b) { return a.last_use_epoch < b.last_use_epoch; });
        if (victim != table_.end() && victim->set != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(device_, pool_, 1, &victim->set);
            table_.erase(victim);
        }
    }

    VkDescriptorSetAllocateInfo ai{};
    ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool     = pool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts        = &layout;

    VkDescriptorSet set = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(device_, &ai, &set) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }

    std::vector<VkWriteDescriptorSet>   writes(binding_count);
    std::vector<VkDescriptorBufferInfo> bi(binding_count);
    std::vector<VkDescriptorImageInfo>  ii(binding_count);
    for (std::uint32_t i = 0; i < binding_count; ++i) {
        const auto& b = bindings[i];
        VkWriteDescriptorSet& w = writes[i];
        w = VkWriteDescriptorSet{};
        w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet          = set;
        w.dstBinding      = b.binding;
        w.dstArrayElement = 0;
        w.descriptorCount = 1;
        w.descriptorType  = b.type;
        if (b.buffer != VK_NULL_HANDLE) {
            bi[i] = { b.buffer, b.offset, b.range };
            w.pBufferInfo = &bi[i];
        } else {
            ii[i] = { b.sampler, b.image_view, VK_IMAGE_LAYOUT_GENERAL };
            w.pImageInfo = &ii[i];
        }
    }
    vkUpdateDescriptorSets(device_, binding_count, writes.data(), 0, nullptr);

    table_.push_back(Entry{ key, set, tick_epoch_ });
    return set;
}

void DescriptorCache::reset() {
    if (pool_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) {
        vkResetDescriptorPool(device_, pool_, 0);
    }
    table_.clear();
    hits_ = misses_ = 0;
}

} // namespace br_core
