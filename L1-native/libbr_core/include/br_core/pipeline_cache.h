/**
 * @file pipeline_cache.h
 * @brief Persistent VkPipelineCache — reads from & writes to
 *        <server_root>/config/blockreality/pipeline_cache.bin so cold
 *        starts after the first successful run drop from ~300 ms to
 *        ~20 ms for compute-pipeline creation (plan §D.5).
 */
#ifndef BR_CORE_PIPELINE_CACHE_H
#define BR_CORE_PIPELINE_CACHE_H

#include <vulkan/vulkan.h>

#include <string>

namespace br_core {

class PipelineCache {
public:
    PipelineCache() = default;
    ~PipelineCache();

    PipelineCache(const PipelineCache&)            = delete;
    PipelineCache& operator=(const PipelineCache&) = delete;

    /**
     * Load the serialised blob from @p path (if present) and create
     * the VkPipelineCache. Empty path → in-memory only.
     */
    bool init(VkDevice device, const std::string& path);

    /** Write the current cache blob back to the path, then destroy. */
    void shutdown();

    VkPipelineCache handle() const { return cache_; }

    /** Flush without destroying — useful during long-running sessions. */
    bool save() const;

private:
    VkDevice         device_ = VK_NULL_HANDLE;
    VkPipelineCache  cache_  = VK_NULL_HANDLE;
    std::string      path_;
};

} // namespace br_core

#endif // BR_CORE_PIPELINE_CACHE_H
