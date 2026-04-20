/**
 * @file cmdbuf_pool.cpp
 * @brief Per-thread VkCommandPool ring.
 */
#include "br_core/cmdbuf_pool.h"

#include <thread>

namespace br_core {

namespace {

std::uint64_t pool_key(std::uint32_t frame) {
    std::uint64_t tid = std::hash<std::thread::id>{}(std::this_thread::get_id());
    return (tid & 0xFFFFFFFFULL) ^ (static_cast<std::uint64_t>(frame) << 32);
}

} // namespace

CmdBufPool::~CmdBufPool() {
    shutdown();
}

bool CmdBufPool::init(VkDevice device, std::uint32_t queue_family) {
    device_       = device;
    queue_family_ = queue_family;
    return device_ != VK_NULL_HANDLE;
}

void CmdBufPool::shutdown() {
    std::lock_guard<std::mutex> lk(mutex_);
    if (device_ != VK_NULL_HANDLE) {
        for (auto& kv : pools_) {
            if (kv.second.pool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device_, kv.second.pool, nullptr);
            }
        }
    }
    pools_.clear();
    device_       = VK_NULL_HANDLE;
    queue_family_ = UINT32_MAX;
}

VkCommandBuffer CmdBufPool::acquire_primary(std::uint32_t frame) {
    if (device_ == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    std::lock_guard<std::mutex> lk(mutex_);
    ThreadPool& tp = pools_[pool_key(frame)];
    if (tp.pool == VK_NULL_HANDLE) {
        VkCommandPoolCreateInfo ci{};
        ci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        ci.flags            = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        ci.queueFamilyIndex = queue_family_;
        if (vkCreateCommandPool(device_, &ci, nullptr, &tp.pool) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
    }
    if (tp.next_primary >= tp.primaries.size()) {
        VkCommandBuffer cb = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool        = tp.pool;
        ai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(device_, &ai, &cb) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        tp.primaries.push_back(cb);
    }
    return tp.primaries[tp.next_primary++];
}

VkCommandBuffer CmdBufPool::acquire_secondary(std::uint32_t frame) {
    if (device_ == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    std::lock_guard<std::mutex> lk(mutex_);
    ThreadPool& tp = pools_[pool_key(frame)];
    if (tp.pool == VK_NULL_HANDLE) {
        VkCommandPoolCreateInfo ci{};
        ci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        ci.flags            = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        ci.queueFamilyIndex = queue_family_;
        if (vkCreateCommandPool(device_, &ci, nullptr, &tp.pool) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
    }
    if (tp.next_secondary >= tp.secondaries.size()) {
        VkCommandBuffer cb = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool        = tp.pool;
        ai.level              = VK_COMMAND_BUFFER_LEVEL_SECONDARY;
        ai.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(device_, &ai, &cb) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        tp.secondaries.push_back(cb);
    }
    return tp.secondaries[tp.next_secondary++];
}

void CmdBufPool::reset_frame(std::uint32_t frame) {
    if (device_ == VK_NULL_HANDLE) return;
    std::lock_guard<std::mutex> lk(mutex_);
    // Reset every pool whose key matches this frame slot, across all threads.
    std::uint64_t frame_bits = static_cast<std::uint64_t>(frame) << 32;
    for (auto& kv : pools_) {
        if ((kv.first & 0xFFFFFFFF00000000ULL) == frame_bits) {
            if (kv.second.pool != VK_NULL_HANDLE) {
                vkResetCommandPool(device_, kv.second.pool, 0);
            }
            kv.second.next_primary   = 0;
            kv.second.next_secondary = 0;
        }
    }
}

} // namespace br_core
