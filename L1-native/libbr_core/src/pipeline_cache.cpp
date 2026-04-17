/**
 * @file pipeline_cache.cpp
 * @brief Persistent VkPipelineCache (plan §D.5).
 */
#include "br_core/pipeline_cache.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <vector>

namespace br_core {

PipelineCache::~PipelineCache() {
    shutdown();
}

bool PipelineCache::init(VkDevice device, const std::string& path) {
    if (cache_ != VK_NULL_HANDLE) return true;
    device_ = device;
    path_   = path;

    std::vector<std::uint8_t> blob;
    if (!path_.empty()) {
        std::ifstream f(path_, std::ios::binary);
        if (f) {
            f.seekg(0, std::ios::end);
            std::streamsize n = f.tellg();
            if (n > 0) {
                f.seekg(0, std::ios::beg);
                blob.resize(static_cast<std::size_t>(n));
                f.read(reinterpret_cast<char*>(blob.data()), n);
            }
        }
    }

    VkPipelineCacheCreateInfo ci{};
    ci.sType           = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    ci.initialDataSize = blob.size();
    ci.pInitialData    = blob.empty() ? nullptr : blob.data();

    if (vkCreatePipelineCache(device_, &ci, nullptr, &cache_) != VK_SUCCESS) {
        // Retry with empty blob in case the cache on disk is corrupt.
        ci.initialDataSize = 0;
        ci.pInitialData    = nullptr;
        if (vkCreatePipelineCache(device_, &ci, nullptr, &cache_) != VK_SUCCESS) {
            cache_ = VK_NULL_HANDLE;
            return false;
        }
    }
    return true;
}

bool PipelineCache::save() const {
    if (cache_ == VK_NULL_HANDLE || device_ == VK_NULL_HANDLE || path_.empty()) return false;

    std::size_t n = 0;
    if (vkGetPipelineCacheData(device_, cache_, &n, nullptr) != VK_SUCCESS || n == 0) {
        return false;
    }
    std::vector<std::uint8_t> blob(n);
    if (vkGetPipelineCacheData(device_, cache_, &n, blob.data()) != VK_SUCCESS) {
        return false;
    }

    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(path_).parent_path(), ec);
    std::ofstream f(path_, std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f.write(reinterpret_cast<const char*>(blob.data()), static_cast<std::streamsize>(n));
    return f.good();
}

void PipelineCache::shutdown() {
    if (cache_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) {
        save();
        vkDestroyPipelineCache(device_, cache_, nullptr);
    }
    cache_  = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
}

} // namespace br_core
