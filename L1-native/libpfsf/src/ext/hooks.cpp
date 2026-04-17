/**
 * @file hooks.cpp
 * @brief Per-island hook table — callbacks fired at tick stage
 *        boundaries by the Phase 6 plan-buffer dispatcher.
 *
 * Phase 5 ships the storage / register / fire primitives; Phase 6
 * actually inserts the fire calls into the tick pipeline. Until then
 * {@code pfsf_hook_fire} is callable from tests so Java-side
 * {@code PFSFAugmentationHost} can be integrated end-to-end without
 * the live solver path.
 *
 * @maps_to PFSFAugmentationHost.java
 * @since v0.3d Phase 5
 */

#include "pfsf/pfsf_extension.h"

#include <array>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>

namespace {

struct island_hooks {
    std::array<pfsf_hook_fn,             PFSF_HOOK_POINT_COUNT> fn{};
    std::array<void*,                    PFSF_HOOK_POINT_COUNT> ud{};
};

struct hooks_state {
    mutable std::shared_mutex mtx;
    std::unordered_map<int32_t, island_hooks> by_island;
};

hooks_state& hs() {
    static hooks_state h;
    return h;
}

inline bool point_in_range(pfsf_hook_point p) {
    return static_cast<int32_t>(p) >= 0
        && static_cast<int32_t>(p) <  PFSF_HOOK_POINT_COUNT;
}

} /* namespace */

extern "C" void pfsf_hook_set(int32_t island_id,
                                pfsf_hook_point pt,
                                pfsf_hook_fn fn,
                                void* user_data) {
    if (!point_in_range(pt)) return;
    auto& h = hs();
    std::unique_lock lk(h.mtx);
    auto& ih = h.by_island[island_id];
    ih.fn[pt] = fn;
    ih.ud[pt] = user_data;
}

extern "C" int32_t pfsf_hook_fire(int32_t island_id,
                                    pfsf_hook_point pt,
                                    int64_t epoch) {
    if (!point_in_range(pt)) return 0;
    pfsf_hook_fn fn = nullptr;
    void*        ud = nullptr;

    {
        auto& h = hs();
        std::shared_lock lk(h.mtx);
        auto it = h.by_island.find(island_id);
        if (it == h.by_island.end()) return 0;
        fn = it->second.fn[pt];
        ud = it->second.ud[pt];
    }

    if (fn == nullptr) return 0;
    fn(island_id, epoch, ud);
    return 1;
}

extern "C" void pfsf_hook_clear(int32_t island_id, pfsf_hook_point pt) {
    if (!point_in_range(pt)) return;
    auto& h = hs();
    std::unique_lock lk(h.mtx);
    auto it = h.by_island.find(island_id);
    if (it == h.by_island.end()) return;
    it->second.fn[pt] = nullptr;
    it->second.ud[pt] = nullptr;
}

extern "C" void pfsf_hook_clear_island(int32_t island_id) {
    auto& h = hs();
    std::unique_lock lk(h.mtx);
    h.by_island.erase(island_id);
}

/* Back-compat engine-flavoured entry: set on a "default" island slot
 * (id = -1) so callers without island context still wire something.
 * Phase 6 supersedes this with island-aware plan-buffer plumbing. */

extern "C" void pfsf_set_hook(pfsf_engine /*e*/,
                                pfsf_hook_point pt,
                                pfsf_hook_fn fn,
                                void* user_data) {
    pfsf_hook_set(/*island_id*/ -1, pt, fn, user_data);
}
