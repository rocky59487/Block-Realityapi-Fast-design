/**
 * @file timeline_sync.h
 * @brief Timeline-semaphore helpers. The core exposes one timeline per
 *        domain (pfsf_timeline, fluid_timeline, render_timeline) so
 *        cross-domain waits can use explicit epoch values rather than
 *        opaque binary fences — preserves the fluid→PFSF 1-tick lag.
 */
#ifndef BR_CORE_TIMELINE_SYNC_H
#define BR_CORE_TIMELINE_SYNC_H

#include <vulkan/vulkan.h>

#include <cstdint>

namespace br_core {

class TimelineSemaphore {
public:
    TimelineSemaphore() = default;
    ~TimelineSemaphore();

    TimelineSemaphore(const TimelineSemaphore&)            = delete;
    TimelineSemaphore& operator=(const TimelineSemaphore&) = delete;

    bool init(VkDevice device, std::uint64_t initial = 0);
    void shutdown();

    VkSemaphore handle() const { return sem_; }

    /** Host-side wait. Returns false on timeout. */
    bool wait(std::uint64_t value, std::uint64_t timeout_ns = UINT64_MAX) const;

    /** Host-side signal. */
    bool signal(std::uint64_t value);

    /** Current value (host-side query). */
    std::uint64_t query() const;

private:
    VkDevice    device_ = VK_NULL_HANDLE;
    VkSemaphore sem_    = VK_NULL_HANDLE;
};

} // namespace br_core

#endif // BR_CORE_TIMELINE_SYNC_H
