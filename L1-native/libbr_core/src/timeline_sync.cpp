/**
 * @file timeline_sync.cpp
 * @brief Timeline semaphore helpers (Vulkan 1.2 core).
 */
#include "br_core/timeline_sync.h"

namespace br_core {

TimelineSemaphore::~TimelineSemaphore() {
    shutdown();
}

bool TimelineSemaphore::init(VkDevice device, std::uint64_t initial) {
    if (sem_ != VK_NULL_HANDLE) return true;
    device_ = device;

    VkSemaphoreTypeCreateInfo tci{};
    tci.sType         = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    tci.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    tci.initialValue  = initial;

    VkSemaphoreCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    ci.pNext = &tci;

    if (vkCreateSemaphore(device_, &ci, nullptr, &sem_) != VK_SUCCESS) {
        sem_ = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

void TimelineSemaphore::shutdown() {
    if (sem_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) {
        vkDestroySemaphore(device_, sem_, nullptr);
    }
    sem_    = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
}

bool TimelineSemaphore::wait(std::uint64_t value, std::uint64_t timeout_ns) const {
    if (sem_ == VK_NULL_HANDLE) return false;
    VkSemaphoreWaitInfo wi{};
    wi.sType          = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    wi.semaphoreCount = 1;
    wi.pSemaphores    = &sem_;
    wi.pValues        = &value;
    return vkWaitSemaphores(device_, &wi, timeout_ns) == VK_SUCCESS;
}

bool TimelineSemaphore::signal(std::uint64_t value) {
    if (sem_ == VK_NULL_HANDLE) return false;
    VkSemaphoreSignalInfo si{};
    si.sType     = VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO;
    si.semaphore = sem_;
    si.value     = value;
    return vkSignalSemaphore(device_, &si) == VK_SUCCESS;
}

std::uint64_t TimelineSemaphore::query() const {
    if (sem_ == VK_NULL_HANDLE) return 0;
    std::uint64_t v = 0;
    vkGetSemaphoreCounterValue(device_, sem_, &v);
    return v;
}

} // namespace br_core
