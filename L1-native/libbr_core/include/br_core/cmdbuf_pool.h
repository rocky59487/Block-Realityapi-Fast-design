/**
 * @file cmdbuf_pool.h
 * @brief Per-thread VkCommandPool ring, one pool per frame-in-flight.
 *        Lets physics/render/async-compute threads reuse allocations
 *        and replaces today's single-pool-with-reset pattern.
 */
#ifndef BR_CORE_CMDBUF_POOL_H
#define BR_CORE_CMDBUF_POOL_H

#include <vulkan/vulkan.h>

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace br_core {

class CmdBufPool {
public:
    static constexpr std::uint32_t MAX_FRAMES_IN_FLIGHT = 2;

    CmdBufPool() = default;
    ~CmdBufPool();

    CmdBufPool(const CmdBufPool&)            = delete;
    CmdBufPool& operator=(const CmdBufPool&) = delete;

    bool init(VkDevice device, std::uint32_t queue_family);
    void shutdown();

    /**
     * Acquire a reset, ready-to-record primary command buffer for the
     * current thread and frame. Pool is lazily created on first use per
     * thread. Returned buffer is owned by the pool — do not free.
     */
    VkCommandBuffer acquire_primary(std::uint32_t frame_index);

    /** As acquire_primary but for a secondary buffer. */
    VkCommandBuffer acquire_secondary(std::uint32_t frame_index);

    /** Reset all pools for the given frame (call once per frame flip). */
    void reset_frame(std::uint32_t frame_index);

private:
    struct ThreadPool {
        VkCommandPool                pool = VK_NULL_HANDLE;
        std::vector<VkCommandBuffer> primaries;
        std::vector<VkCommandBuffer> secondaries;
        std::uint32_t                next_primary   = 0;
        std::uint32_t                next_secondary = 0;
    };

    VkDevice                        device_        = VK_NULL_HANDLE;
    std::uint32_t                   queue_family_  = UINT32_MAX;
    std::mutex                      mutex_;
    std::unordered_map<std::uint64_t, ThreadPool> pools_;   // key = thread_id ^ (frame << 32)
};

} // namespace br_core

#endif // BR_CORE_CMDBUF_POOL_H
