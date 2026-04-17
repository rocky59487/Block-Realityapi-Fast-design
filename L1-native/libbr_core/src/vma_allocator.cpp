/**
 * @file vma_allocator.cpp
 * @brief VMA allocator with PFSF / Fluid / Other partition pools.
 */
#define VMA_IMPLEMENTATION
#include <vk_mem_alloc.h>

#include "br_core/vma_allocator.h"
#include "br_core/br_core.h"            // for Partition
#include "br_core/vulkan_device.h"

#include <cstdio>

namespace br_core {

namespace {

constexpr double kShareMap[3] = {0.70, 0.20, 0.10};   // PFSF / FLUID / OTHER

std::uint32_t partition_index(Partition p) {
    return static_cast<std::uint32_t>(p);
}

} // namespace

VmaAllocatorHandle::~VmaAllocatorHandle() {
    shutdown();
}

bool VmaAllocatorHandle::init(const VulkanDevice& dev, std::uint64_t vram_budget) {
    if (allocator_ != nullptr) return true;
    if (!dev.is_ready())       return false;

    VmaAllocatorCreateInfo ci{};
    ci.physicalDevice = dev.physical_device();
    ci.device         = dev.device();
    ci.instance       = dev.instance();
    ci.vulkanApiVersion = VK_API_VERSION_1_2;

    if (vmaCreateAllocator(&ci, &allocator_) != VK_SUCCESS) {
        std::fprintf(stderr, "[br_core] vmaCreateAllocator failed\n");
        allocator_ = nullptr;
        return false;
    }

    for (int i = 0; i < 3; ++i) {
        budgets_[i] = static_cast<std::uint64_t>(vram_budget * kShareMap[i]);
        usage_[i]   = 0;
    }
    // Partition pools are left implicit: we track the budget in-process
    // and route allocations via the default allocator. VMA's own pools
    // require a fixed memory-type index, and the right type depends on
    // the buffer usage — see alloc_device_storage below.
    return true;
}

void VmaAllocatorHandle::shutdown() {
    if (allocator_ != nullptr) {
        vmaDestroyAllocator(allocator_);
        allocator_ = nullptr;
    }
    pool_pfsf_ = pool_fluid_ = pool_other_ = nullptr;
    for (int i = 0; i < 3; ++i) { budgets_[i] = 0; usage_[i] = 0; }
}

VmaBufferHandle VmaAllocatorHandle::alloc_device_storage(Partition part,
                                                          VkDeviceSize size,
                                                          VkBufferUsageFlags extra_usage) {
    VmaBufferHandle out{};
    if (allocator_ == nullptr || size == 0) return out;

    std::uint32_t pi = partition_index(part);
    if (budgets_[pi] != 0 && usage_[pi] + size > budgets_[pi]) {
        std::fprintf(stderr, "[br_core] partition %u budget exceeded (req=%llu used=%llu bud=%llu)\n",
                     pi, (unsigned long long)size, (unsigned long long)usage_[pi],
                     (unsigned long long)budgets_[pi]);
        return out;
    }

    VkBufferCreateInfo bci{};
    bci.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size        = size;
    bci.usage       = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                      VK_BUFFER_USAGE_TRANSFER_SRC_BIT   |
                      VK_BUFFER_USAGE_TRANSFER_DST_BIT   |
                      extra_usage;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo aci{};
    aci.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;

    VmaAllocationInfo info{};
    VkBuffer buf = VK_NULL_HANDLE;
    VmaAllocation alloc = nullptr;
    if (vmaCreateBuffer(allocator_, &bci, &aci, &buf, &alloc, &info) != VK_SUCCESS) {
        return out;
    }

    out.buffer     = buf;
    out.allocation = alloc;
    out.mapped     = nullptr;
    out.size       = size;
    usage_[pi] += size;
    return out;
}

VmaBufferHandle VmaAllocatorHandle::alloc_staging(VkDeviceSize size) {
    VmaBufferHandle out{};
    if (allocator_ == nullptr || size == 0) return out;

    VkBufferCreateInfo bci{};
    bci.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size        = size;
    bci.usage       = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo aci{};
    aci.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    aci.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
                VMA_ALLOCATION_CREATE_MAPPED_BIT;

    VmaAllocationInfo info{};
    VkBuffer buf = VK_NULL_HANDLE;
    VmaAllocation alloc = nullptr;
    if (vmaCreateBuffer(allocator_, &bci, &aci, &buf, &alloc, &info) != VK_SUCCESS) {
        return out;
    }
    out.buffer     = buf;
    out.allocation = alloc;
    out.mapped     = info.pMappedData;
    out.size       = size;
    return out;
}

void VmaAllocatorHandle::free(VmaBufferHandle& h) {
    if (h.buffer == VK_NULL_HANDLE || allocator_ == nullptr) return;
    vmaDestroyBuffer(allocator_, h.buffer, h.allocation);
    h.buffer     = VK_NULL_HANDLE;
    h.allocation = nullptr;
    h.mapped     = nullptr;
    h.size       = 0;
}

std::uint64_t VmaAllocatorHandle::budget_bytes(Partition part) const {
    return budgets_[partition_index(part)];
}

std::uint64_t VmaAllocatorHandle::used_bytes(Partition part) const {
    return usage_[partition_index(part)];
}

} // namespace br_core
