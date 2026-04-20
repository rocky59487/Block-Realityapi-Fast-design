/**
 * @file br_core.h
 * @brief libbr_core — shared Vulkan / VMA / pipeline-cache foundation
 *        for Block Reality v0.3c native libraries.
 *
 * Singleton layout:
 *   br_core::get_singleton() returns the process-wide VulkanDevice.
 *   First call initializes Vulkan; later calls return the cached handle.
 *   All three domain libraries (libblockreality_pfsf/_fluid/_render)
 *   share the same VkInstance + VkDevice + VMA allocator — see
 *   /root/.claude/plans/c-java-wiggly-kettle.md §D.1 for rationale.
 *
 * Thread-safety: get_singleton() is internally synchronised.
 * VkDevice access respects Vulkan's "externally synchronised"
 * contract — callers must own the queue/command-pool they submit on.
 */
#ifndef BR_CORE_H
#define BR_CORE_H

#include <cstdint>
#include <memory>

#include "vulkan_device.h"
#include "vma_allocator.h"
#include "descriptor_cache.h"
#include "pipeline_cache.h"
#include "spirv_registry.h"
#include "cmdbuf_pool.h"
#include "timeline_sync.h"
#include "jni_helpers.h"
#include "callback_bus.h"

namespace br_core {

/**
 * Process-wide foundation. Exactly one instance per process.
 * Lifetime matches the dynamic library load — destruction happens
 * at dlclose / process exit via the registered atexit handler.
 */
struct Core {
    VulkanDevice     device;
    VmaAllocatorHandle vma;
    DescriptorCache  descriptors;
    PipelineCache    pipelines;
    SpirvRegistry    spirv;
    CmdBufPool       cmdbuf;
    CallbackBus      callbacks;
};

/**
 * Returns the process-wide Core instance, initializing it on first call.
 * Returns nullptr and logs if Vulkan initialisation failed — callers
 * must tolerate that and fall back cleanly.
 */
Core* get_singleton();

/**
 * Returns the Core instance if it was already initialized, WITHOUT
 * triggering bring_up(). Returns nullptr if the singleton has not yet
 * been initialized. Use this in code paths that must not create a second
 * VkInstance/VkDevice as a side-effect (e.g. device-explicit helpers
 * called from libpfsf before the br_core singleton is up).
 */
Core* peek_singleton();

/**
 * Forcibly tear the core down (test-only). Normal programs let
 * the atexit hook clean up on unload.
 */
void shutdown_singleton();

} // namespace br_core

#endif // BR_CORE_H
