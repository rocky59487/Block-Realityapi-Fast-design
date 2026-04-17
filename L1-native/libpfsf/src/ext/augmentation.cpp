/**
 * @file augmentation.cpp
 * @brief Process-wide augmentation slot registry — the SPI storage layer
 *        exposed by {@code compute.v5} / {@code extension.v1}.
 *
 * External mods implement a Java SPI (IThermalManager, ICableManager, …),
 * register a per-voxel DirectByteBuffer here via PFSFAugmentationHost,
 * and the tick pipeline (Phase 6 plan buffer) reads the stored slot at
 * the matching hook point. Storage is intentionally compact and thread-
 * safe: many mods may wire themselves in during world-load.
 *
 * @maps_to PFSFAugmentationHost.java — Java-side facade.
 * @since v0.3d Phase 5
 */

#include "pfsf/pfsf_extension.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>
#include <vector>

namespace {

/* Slot count per island == augmentation kinds (1..9). Slot index 0 is
 * reserved sentinel. Using a fixed small array keeps lookups O(1) and
 * dodges map allocation on the hot path. */
constexpr int32_t AUG_KIND_MAX = 9;

struct island_augs {
    std::array<pfsf_aug_slot, AUG_KIND_MAX + 1> slots{};
    std::array<uint8_t,       AUG_KIND_MAX + 1> present{};
    int32_t                                     count = 0;
};

struct registry_state {
    mutable std::shared_mutex mtx;
    std::unordered_map<int32_t, island_augs> by_island;
};

registry_state& reg() {
    static registry_state r;
    return r;
}

inline bool kind_in_range(pfsf_augmentation_kind k) {
    return static_cast<int32_t>(k) >= 1
        && static_cast<int32_t>(k) <= AUG_KIND_MAX;
}

} /* namespace */

extern "C" pfsf_result pfsf_aug_register(int32_t island_id,
                                           const pfsf_aug_slot* slot) {
    if (slot == nullptr)          return PFSF_ERROR_INVALID_ARG;
    if (!kind_in_range(slot->kind)) return PFSF_ERROR_INVALID_ARG;

    /* Tolerate future-sized slots: memcpy up to whichever is smaller
     * between the caller struct and ours. struct_bytes must be at
     * least large enough to cover the kind + dbb_addr fields. */
    const int32_t min_bytes =
        static_cast<int32_t>(offsetof(pfsf_aug_slot, version)
                           + sizeof(((pfsf_aug_slot*)0)->version));
    if (slot->struct_bytes > 0 && slot->struct_bytes < min_bytes)
        return PFSF_ERROR_INVALID_ARG;

    pfsf_aug_slot copy{};
    copy.struct_bytes = static_cast<int32_t>(sizeof(pfsf_aug_slot));
    const size_t to_copy = (slot->struct_bytes > 0)
        ? std::min(static_cast<size_t>(slot->struct_bytes),
                   sizeof(pfsf_aug_slot))
        : sizeof(pfsf_aug_slot);
    std::memcpy(&copy, slot, to_copy);
    copy.struct_bytes = static_cast<int32_t>(sizeof(pfsf_aug_slot));

    auto& r = reg();
    std::unique_lock lk(r.mtx);
    auto& ia = r.by_island[island_id];
    const int32_t idx = static_cast<int32_t>(copy.kind);
    if (!ia.present[idx]) {
        ia.present[idx] = 1;
        ia.count += 1;
    }
    ia.slots[idx] = copy;
    return PFSF_OK;
}

extern "C" void pfsf_aug_clear(int32_t island_id,
                                 pfsf_augmentation_kind kind) {
    if (!kind_in_range(kind)) return;
    auto& r = reg();
    std::unique_lock lk(r.mtx);
    auto it = r.by_island.find(island_id);
    if (it == r.by_island.end()) return;
    const int32_t idx = static_cast<int32_t>(kind);
    if (it->second.present[idx]) {
        it->second.present[idx] = 0;
        it->second.slots[idx]   = pfsf_aug_slot{};
        it->second.count       -= 1;
    }
    if (it->second.count <= 0) r.by_island.erase(it);
}

extern "C" void pfsf_aug_clear_island(int32_t island_id) {
    auto& r = reg();
    std::unique_lock lk(r.mtx);
    r.by_island.erase(island_id);
}

extern "C" int32_t pfsf_aug_query(int32_t island_id,
                                    pfsf_augmentation_kind kind,
                                    pfsf_aug_slot* out) {
    if (out == nullptr)            return 0;
    if (!kind_in_range(kind))      return 0;

    auto& r = reg();
    std::shared_lock lk(r.mtx);
    auto it = r.by_island.find(island_id);
    if (it == r.by_island.end()) return 0;
    const int32_t idx = static_cast<int32_t>(kind);
    if (!it->second.present[idx]) return 0;
    *out = it->second.slots[idx];
    return 1;
}

extern "C" int32_t pfsf_aug_island_count(int32_t island_id) {
    auto& r = reg();
    std::shared_lock lk(r.mtx);
    auto it = r.by_island.find(island_id);
    if (it == r.by_island.end()) return 0;
    return it->second.count;
}

/* Back-compat wrappers for the engine-flavoured entry points declared
 * in Phase 0's header. The engine handle is ignored for now — Phase 6
 * plan buffer will route real engine state through the same storage. */

extern "C" pfsf_result pfsf_register_augmentation(pfsf_engine /*e*/,
                                                    int32_t island_id,
                                                    const pfsf_aug_slot* slot) {
    return pfsf_aug_register(island_id, slot);
}

extern "C" void pfsf_clear_augmentation(pfsf_engine /*e*/,
                                          int32_t island_id,
                                          pfsf_augmentation_kind kind) {
    pfsf_aug_clear(island_id, kind);
}
