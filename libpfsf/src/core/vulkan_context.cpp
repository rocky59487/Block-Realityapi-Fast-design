/**
 * @file vulkan_context.cpp
 * @brief Vulkan compute context — init, shutdown, buffer ops.
 */
#include "vulkan_context.h"
#include <cstring>
#include <vector>
#include <cstdio>

namespace pfsf {

VulkanContext::VulkanContext() = default;

VulkanContext::~VulkanContext() {
    shutdown();
}

bool VulkanContext::init() {
    if (available_) return true;

    // ── Instance ──
    VkApplicationInfo appInfo{};
    appInfo.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName   = "libpfsf";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.pEngineName        = "BlockReality-PFSF";
    appInfo.engineVersion      = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion         = VK_API_VERSION_1_2;

    VkInstanceCreateInfo instCI{};
    instCI.sType            = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instCI.pApplicationInfo = &appInfo;

    if (vkCreateInstance(&instCI, nullptr, &instance_) != VK_SUCCESS) {
        fprintf(stderr, "[libpfsf] vkCreateInstance failed\n");
        return false;
    }

    // ── Physical device ──
    if (!selectPhysicalDevice()) {
        fprintf(stderr, "[libpfsf] No compute-capable GPU found\n");
        shutdown();
        return false;
    }

    // ── Queue family ──
    int qf = findComputeQueueFamily();
    if (qf < 0) {
        fprintf(stderr, "[libpfsf] No compute queue family\n");
        shutdown();
        return false;
    }
    computeQueueFamily_ = static_cast<uint32_t>(qf);

    // ── Logical device ──
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueCI{};
    queueCI.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCI.queueFamilyIndex = computeQueueFamily_;
    queueCI.queueCount       = 1;
    queueCI.pQueuePriorities = &priority;

    VkPhysicalDeviceFeatures features{};

    VkDeviceCreateInfo deviceCI{};
    deviceCI.sType                = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCI.queueCreateInfoCount = 1;
    deviceCI.pQueueCreateInfos    = &queueCI;
    deviceCI.pEnabledFeatures     = &features;

    if (vkCreateDevice(physDevice_, &deviceCI, nullptr, &device_) != VK_SUCCESS) {
        fprintf(stderr, "[libpfsf] vkCreateDevice failed\n");
        shutdown();
        return false;
    }

    vkGetDeviceQueue(device_, computeQueueFamily_, 0, &computeQueue_);

    // ── Command pool ──
    VkCommandPoolCreateInfo poolCI{};
    poolCI.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolCI.queueFamilyIndex = computeQueueFamily_;
    poolCI.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    if (vkCreateCommandPool(device_, &poolCI, nullptr, &cmdPool_) != VK_SUCCESS) {
        fprintf(stderr, "[libpfsf] vkCreateCommandPool failed\n");
        shutdown();
        return false;
    }

    available_ = true;
    fprintf(stderr, "[libpfsf] Vulkan initialized: %s (VRAM: %lld MB)\n",
            deviceName_.c_str(), (long long)(deviceLocalBytes_ / (1024 * 1024)));
    return true;
}

void VulkanContext::shutdown() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);

        if (cmdPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, cmdPool_, nullptr);
            cmdPool_ = VK_NULL_HANDLE;
        }
        vkDestroyDevice(device_, nullptr);
        device_       = VK_NULL_HANDLE;
        computeQueue_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    physDevice_ = VK_NULL_HANDLE;
    available_  = false;
}

// ═══ Physical device selection ═══

bool VulkanContext::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) return false;

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    // Prefer discrete GPU, fall back to any compute-capable
    VkPhysicalDevice fallback = VK_NULL_HANDLE;
    for (auto& pd : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(pd, &props);

        // Check compute queue support
        uint32_t qfCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
        std::vector<VkQueueFamilyProperties> qfProps(qfCount);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qfProps.data());

        bool hasCompute = false;
        for (auto& qf : qfProps) {
            if (qf.queueFlags & VK_QUEUE_COMPUTE_BIT) { hasCompute = true; break; }
        }
        if (!hasCompute) continue;

        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            physDevice_ = pd;
            deviceName_ = props.deviceName;
            break;
        }
        if (fallback == VK_NULL_HANDLE) {
            fallback    = pd;
            deviceName_ = props.deviceName;
        }
    }
    if (physDevice_ == VK_NULL_HANDLE) physDevice_ = fallback;
    if (physDevice_ == VK_NULL_HANDLE) return false;

    // Query VRAM
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(physDevice_, &memProps);
    deviceLocalBytes_ = 0;
    for (uint32_t i = 0; i < memProps.memoryHeapCount; i++) {
        if (memProps.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
            deviceLocalBytes_ += static_cast<int64_t>(memProps.memoryHeaps[i].size);
        }
    }
    return true;
}

int VulkanContext::findComputeQueueFamily() {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physDevice_, &count, nullptr);
    std::vector<VkQueueFamilyProperties> props(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physDevice_, &count, props.data());

    // Prefer dedicated compute (no graphics)
    for (uint32_t i = 0; i < count; i++) {
        if ((props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) &&
            !(props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            return static_cast<int>(i);
        }
    }
    // Fall back to any compute
    for (uint32_t i = 0; i < count; i++) {
        if (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

uint32_t VulkanContext::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(physDevice_, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

// ═══ Buffer operations ═══

bool VulkanContext::allocBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                VkBuffer* outBuffer, VkDeviceMemory* outMemory) {
    VkBufferCreateInfo bufCI{};
    bufCI.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufCI.size  = size;
    bufCI.usage = usage;
    bufCI.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device_, &bufCI, nullptr, outBuffer) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, *outBuffer, &memReqs);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize  = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (allocInfo.memoryTypeIndex == UINT32_MAX ||
        vkAllocateMemory(device_, &allocInfo, nullptr, outMemory) != VK_SUCCESS) {
        vkDestroyBuffer(device_, *outBuffer, nullptr);
        *outBuffer = VK_NULL_HANDLE;
        return false;
    }
    vkBindBufferMemory(device_, *outBuffer, *outMemory, 0);
    return true;
}

void VulkanContext::freeBuffer(VkBuffer buffer, VkDeviceMemory memory) {
    if (device_ == VK_NULL_HANDLE) return;
    if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, buffer, nullptr);
    if (memory != VK_NULL_HANDLE) vkFreeMemory(device_, memory, nullptr);
}

void* VulkanContext::mapBuffer(VkDeviceMemory memory, VkDeviceSize size) {
    void* data = nullptr;
    vkMapMemory(device_, memory, 0, size, 0, &data);
    return data;
}

void VulkanContext::unmapBuffer(VkDeviceMemory memory) {
    vkUnmapMemory(device_, memory);
}

// ═══ Command buffer ═══

VkCommandBuffer VulkanContext::allocCmdBuffer() {
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool        = cmdPool_;
    allocInfo.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    VkCommandBuffer cmdBuf;
    if (vkAllocateCommandBuffers(device_, &allocInfo, &cmdBuf) != VK_SUCCESS)
        return VK_NULL_HANDLE;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmdBuf, &beginInfo);
    return cmdBuf;
}

void VulkanContext::submitAndWait(VkCommandBuffer cmdBuf) {
    vkEndCommandBuffer(cmdBuf);

    VkSubmitInfo submitInfo{};
    submitInfo.sType              = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers    = &cmdBuf;

    vkQueueSubmit(computeQueue_, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(computeQueue_);
    vkFreeCommandBuffers(device_, cmdPool_, 1, &cmdBuf);
}

// ═══ Pipeline helpers ═══

VkShaderModule VulkanContext::createShaderModule(const uint32_t* spirv, size_t sizeBytes) {
    VkShaderModuleCreateInfo ci{};
    ci.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = sizeBytes;
    ci.pCode    = spirv;

    VkShaderModule module;
    if (vkCreateShaderModule(device_, &ci, nullptr, &module) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return module;
}

VkDescriptorPool VulkanContext::createDescriptorPool(uint32_t maxSets, uint32_t maxDescriptors) {
    VkDescriptorPoolSize poolSize{};
    poolSize.type            = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = maxDescriptors;

    VkDescriptorPoolCreateInfo ci{};
    ci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.maxSets       = maxSets;
    ci.poolSizeCount = 1;
    ci.pPoolSizes    = &poolSize;

    VkDescriptorPool pool;
    if (vkCreateDescriptorPool(device_, &ci, nullptr, &pool) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return pool;
}

VkDescriptorSet VulkanContext::allocDescriptorSet(VkDescriptorPool pool,
                                                   VkDescriptorSetLayout layout) {
    VkDescriptorSetAllocateInfo ai{};
    ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool     = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts        = &layout;

    VkDescriptorSet set;
    if (vkAllocateDescriptorSets(device_, &ai, &set) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return set;
}

} // namespace pfsf
