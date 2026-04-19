/**
 * @file br_core.cpp
 * @brief Process-wide Core singleton — lazy init, atexit shutdown.
 */
#include "br_core/br_core.h"

#include <vulkan/vulkan.h>

#include <cstdio>
#include <cstdlib>
#include <mutex>

namespace br_core {

namespace {

std::mutex                s_mutex;
Core*                     s_core         = nullptr;
bool                      s_init_failed  = false;
bool                      s_atexit_wired = false;

constexpr std::uint64_t kDefaultVramBudget = 4ULL * 1024 * 1024 * 1024;   // 4 GiB

void core_atexit_shutdown() {
    shutdown_singleton();
}

bool bring_up(Core& c) {
    if (!c.device.init(nullptr)) {
        std::fprintf(stderr, "[br_core] VulkanDevice::init failed\n");
        return false;
    }
    if (!c.vma.init(c.device, kDefaultVramBudget)) {
        std::fprintf(stderr, "[br_core] VMA init failed\n");
        c.device.shutdown();
        return false;
    }
    c.spirv.consume_deferred();
    // PR#187 capy-ai R24: descriptors/pipelines/cmdbuf all return bool and
    // fail in realistic low-VRAM / driver-bug cases. Previously the returns
    // were dropped, so get_singleton() flipped s_init_failed = false while
    // the compute path had a half-initialised Core — every first dispatch
    // would then crash inside descriptors.acquire() / cmdbuf.begin().
    // Check each, and on failure tear down the ones that succeeded so the
    // next bring_up() attempt starts from a clean slate (the s_init_failed
    // sticky flag still gates retries, but the unwind keeps VRAM accounting
    // honest and prevents the atexit hook from double-destroying).
    if (!c.descriptors.init(c.device.device())) {
        std::fprintf(stderr, "[br_core] DescriptorCache::init failed\n");
        c.vma.shutdown();
        c.device.shutdown();
        return false;
    }
    if (!c.pipelines.init(c.device.device(), std::string{})) {
        std::fprintf(stderr, "[br_core] PipelineCache::init failed\n");
        c.descriptors.shutdown();
        c.vma.shutdown();
        c.device.shutdown();
        return false;
    }
    if (!c.cmdbuf.init(c.device.device(), c.device.graphics_family())) {
        std::fprintf(stderr, "[br_core] CmdbufPool::init failed\n");
        c.pipelines.shutdown();
        c.descriptors.shutdown();
        c.vma.shutdown();
        c.device.shutdown();
        return false;
    }
    return true;
}

} // namespace

Core* get_singleton() {
    std::lock_guard<std::mutex> lk(s_mutex);
    if (s_core != nullptr) return s_core;
    if (s_init_failed)     return nullptr;

    Core* c = new (std::nothrow) Core();
    if (c == nullptr) { s_init_failed = true; return nullptr; }

    if (!bring_up(*c)) {
        delete c;
        s_init_failed = true;
        return nullptr;
    }

    s_core = c;
    if (!s_atexit_wired) {
        std::atexit(&core_atexit_shutdown);
        s_atexit_wired = true;
    }
    return s_core;
}

Core* peek_singleton() {
    std::lock_guard<std::mutex> lk(s_mutex);
    return s_core;  // null if not yet initialized — never calls bring_up()
}

void shutdown_singleton() {
    std::lock_guard<std::mutex> lk(s_mutex);
    if (s_core == nullptr) return;

    // PR#187 capy-ai R36: Vulkan object-destruction rules forbid freeing
    // resources while the device still has pending work that references
    // them. VulkanDevice::shutdown() internally calls vkDeviceWaitIdle,
    // but it runs LAST — after cmdbuf/pipelines/descriptors/vma have
    // already been destroyed — so any in-flight command buffer or
    // pipeline referenced by the queue would target destroyed handles,
    // triggering validation errors or driver use-after-free on shutdown.
    //
    // Wait for the device to drain BEFORE destroying any child resources.
    // VulkanDevice::shutdown() will call vkDeviceWaitIdle again, which is
    // a harmless no-op on an already-idle device.
    if (VkDevice dev = s_core->device.device(); dev != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(dev);
    }

    s_core->cmdbuf.shutdown();
    s_core->pipelines.shutdown();
    s_core->descriptors.shutdown();
    s_core->vma.shutdown();
    s_core->device.shutdown();
    delete s_core;
    s_core = nullptr;
}

} // namespace br_core
