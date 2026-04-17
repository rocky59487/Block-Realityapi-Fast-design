/**
 * @file spirv_registry.cpp
 * @brief SPIR-V blob registry implementation.
 */
#include "br_core/spirv_registry.h"

namespace br_core {

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

} // namespace br_core
