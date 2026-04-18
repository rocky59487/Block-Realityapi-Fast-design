/**
 * @file br_core.cpp
 * @brief Process-wide Core singleton — lazy init, atexit shutdown.
 */
#include "br_core/br_core.h"

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
        return false;
    }
    c.spirv.consume_deferred();
    c.descriptors.init(c.device.device());
    c.pipelines.init(c.device.device(), std::string{});
    c.cmdbuf.init(c.device.device(), c.device.graphics_family());
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
    s_core->cmdbuf.shutdown();
    s_core->pipelines.shutdown();
    s_core->descriptors.shutdown();
    s_core->vma.shutdown();
    s_core->device.shutdown();
    delete s_core;
    s_core = nullptr;
}

} // namespace br_core
