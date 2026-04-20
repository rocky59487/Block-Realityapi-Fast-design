/**
 * @file spirv_registry.cpp
 * @brief SPIR-V blob registry implementation.
 */
#include "br_core/spirv_registry.h"

#include <mutex>
#include <string>
#include <vector>

namespace br_core {

namespace {
struct DeferredBlob {
    const char* name;
    const std::uint32_t* words;
    std::uint32_t word_count;
};

// Safe static initialization: use a function to return the static list.
auto& deferred_blobs() {
    static std::vector<DeferredBlob> list;
    return list;
}
static std::mutex s_deferred_mutex;
} // namespace

void SpirvRegistry::register_blob(std::string_view name,
                                   const std::uint32_t* words,
                                   std::uint32_t word_count) {
    blobs_[std::string(name)] = SpirvBlob{ words, word_count };
}

SpirvBlob SpirvRegistry::lookup(std::string_view name) const {
    auto it = blobs_.find(std::string(name));
    if (it == blobs_.end()) return SpirvBlob{ nullptr, 0 };
    return it->second;
}

void SpirvRegistry::add_deferred_blob(const char* name, const std::uint32_t* words, std::uint32_t word_count) {
    std::lock_guard<std::mutex> lock(s_deferred_mutex);
    deferred_blobs().push_back({name, words, word_count});
}

SpirvBlob SpirvRegistry::lookup_deferred(std::string_view name) {
    std::lock_guard<std::mutex> lock(s_deferred_mutex);
    for (const auto& b : deferred_blobs()) {
        if (b.name && name == b.name) {
            return SpirvBlob{ b.words, b.word_count };
        }
    }
    return SpirvBlob{ nullptr, 0 };
}

void SpirvRegistry::consume_deferred() {
    std::lock_guard<std::mutex> lock(s_deferred_mutex);
    for (const auto& b : deferred_blobs()) {
        register_blob(b.name, b.words, b.word_count);
    }
    deferred_blobs().clear();
}

} // namespace br_core
