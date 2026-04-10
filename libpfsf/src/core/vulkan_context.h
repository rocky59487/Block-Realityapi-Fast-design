/**
 * @file vulkan_context.h
 * @brief Internal Vulkan compute context — device, queue, command pool, VMA.
 *
 * Mirrors Java VulkanComputeContext but owns its Vulkan instance
 * (no sharing with the Java-side BRVulkanDevice).
 */
#pragma once

#include <vulkan/vulkan.h>
#include <vk_mem_alloc.h>
#include <cstdint>
#include <string>
#include <unordered_map>

namespace pfsf {

class VulkanContext {
public:
    VulkanContext();
    ~VulkanContext();

    VulkanContext(const VulkanContext&) = delete;
    VulkanContext& operator=(const VulkanContext&) = delete;

    /** Initialize Vulkan instance, device, queue, command pool. */
    bool init();

    /** Destroy all Vulkan resources. Safe to call multiple times. */
    void shutdown();

    bool isAvailable() const { return available_; }
    const std::string& deviceName() const { return deviceName_; }

    // ── Accessors ──
    VkInstance       instance()     const { return instance_; }
    VkPhysicalDevice physDevice()   const { return physDevice_; }
    VkDevice         device()       const { return device_; }
    VkQueue          computeQueue() const { return computeQueue_; }
    uint32_t         queueFamily()  const { return computeQueueFamily_; }
    VkCommandPool    cmdPool()      const { return cmdPool_; }

    // ── Buffer operations (VMA-backed) ──

    /**
     * Allocate a device-local buffer via VMA sub-allocation.
     * outMemory is set to VK_NULL_HANDLE — VMA manages the backing memory internally.
     * The VmaAllocation is tracked internally and retrieved via freeBuffer().
     */
    bool allocBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                     VkBuffer* outBuffer, VkDeviceMemory* outMemory);

    /** Free a VMA-managed buffer. The memory parameter is ignored (VMA owns it). */
    void freeBuffer(VkBuffer buffer, VkDeviceMemory memory);

    /**
     * Map a host-visible buffer (staging) for CPU write.
     * Uses VMA-tracked allocation for the given buffer handle.
     */
    void* mapBuffer(VkBuffer buffer, VkDeviceSize size);

    /** Unmap a previously mapped buffer. */
    void unmapBuffer(VkBuffer buffer);

    // ── Command buffer ──

    /** Allocate a one-shot command buffer (begin state). */
    VkCommandBuffer allocCmdBuffer();

    /** End + submit + wait + free a one-shot command buffer. */
    void submitAndWait(VkCommandBuffer cmdBuf);

    // ── Pipeline helpers ──

    VkShaderModule createShaderModule(const uint32_t* spirv, size_t sizeBytes);
    VkDescriptorPool createDescriptorPool(uint32_t maxSets, uint32_t maxDescriptors);
    VkDescriptorSet allocDescriptorSet(VkDescriptorPool pool, VkDescriptorSetLayout layout);

    // ── Memory queries ──
    int64_t deviceLocalBytes() const { return deviceLocalBytes_; }

private:
    bool selectPhysicalDevice();
    int  findComputeQueueFamily();
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props);

    bool        available_ = false;
    std::string deviceName_ = "unknown";

    VkInstance       instance_       = VK_NULL_HANDLE;
    VkPhysicalDevice physDevice_     = VK_NULL_HANDLE;
    VkDevice         device_         = VK_NULL_HANDLE;
    VkQueue          computeQueue_   = VK_NULL_HANDLE;
    uint32_t         computeQueueFamily_ = UINT32_MAX;
    VkCommandPool    cmdPool_        = VK_NULL_HANDLE;

    // VMA allocator — owns all GPU buffer sub-allocations
    VmaAllocator     allocator_      = VK_NULL_HANDLE;
    // Map from VkBuffer handle → VmaAllocation (for deferred free + map/unmap)
    std::unordered_map<VkBuffer, VmaAllocation> allocationMap_;

    int64_t          deviceLocalBytes_ = 0;
};

} // namespace pfsf
