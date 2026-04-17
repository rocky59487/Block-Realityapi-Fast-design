/**
 * @file vma_allocator.h
 * @brief Process-wide VMA allocator with PFSF/Fluid/Other partition pools.
 *        Mirrors Java constants in VulkanComputeContext.java:57-59.
 */
#ifndef BR_CORE_VMA_ALLOCATOR_H
#define BR_CORE_VMA_ALLOCATOR_H

#include <vulkan/vulkan.h>

#include <cstdint>

// Forward-declared to keep <vk_mem_alloc.h> out of public headers.
struct VmaAllocator_T;
struct VmaPool_T;
struct VmaAllocation_T;

namespace br_core {

class VulkanDevice;

/** Partition id — must match Java VulkanComputeContext constants. */
enum class Partition : std::uint32_t;    // defined in br_core.h

struct VmaBufferHandle {
    VkBuffer         buffer     = VK_NULL_HANDLE;
    VmaAllocation_T* allocation = nullptr;
    void*            mapped     = nullptr;   ///< non-null iff host-visible + persistently mapped
    VkDeviceSize     size       = 0;
};

class VmaAllocatorHandle {
public:
    VmaAllocatorHandle() = default;
    ~VmaAllocatorHandle();

    VmaAllocatorHandle(const VmaAllocatorHandle&)            = delete;
    VmaAllocatorHandle& operator=(const VmaAllocatorHandle&) = delete;

    bool init(const VulkanDevice& dev, std::uint64_t vram_budget_bytes);
    void shutdown();

    /**
     * Allocate a device-local storage buffer within the given partition.
     * Budget overflow returns an empty handle (buffer == VK_NULL_HANDLE).
     */
    VmaBufferHandle alloc_device_storage(Partition part,
                                          VkDeviceSize size,
                                          VkBufferUsageFlags extra_usage = 0);

    /** Allocate a host-visible, persistently mapped staging buffer. */
    VmaBufferHandle alloc_staging(VkDeviceSize size);

    /** Free a buffer previously returned by alloc_*. NOP on empty handle. */
    void free(VmaBufferHandle& h);

    // ── Budget inspection ──────────────────────────────────────────
    std::uint64_t budget_bytes(Partition part) const;
    std::uint64_t used_bytes(Partition part)   const;

    VmaAllocator_T* raw() const { return allocator_; }

private:
    VmaAllocator_T* allocator_ = nullptr;
    VmaPool_T*      pool_pfsf_  = nullptr;
    VmaPool_T*      pool_fluid_ = nullptr;
    VmaPool_T*      pool_other_ = nullptr;
    std::uint64_t   budgets_[3]{};   // [PFSF, FLUID, OTHER]
    std::uint64_t   usage_[3]{};
};

} // namespace br_core

#endif // BR_CORE_VMA_ALLOCATOR_H
