/**
 * @file plan_dispatcher.cpp
 * @brief v0.3d Phase 6 — tick plan opcode dispatcher.
 *
 * Parses a caller-supplied DirectByteBuffer and walks a sequence of
 * length-prefixed opcode records, dispatching each to a stateless
 * handler. All reads are bounds-checked against the caller-declared
 * plan_bytes; malformed plans return PFSF_ERROR_INVALID_ARG with the
 * failing opcode index written back so Java can surface the culprit.
 *
 * The hot-path discipline is: zero heap allocation, zero string
 * formatting, zero global mutation per opcode beyond the advertised
 * side effect (aug-registry mutation / hook fire / test counter).
 *
 * Phase 6 ships with 5 opcodes (NO_OP, INCR_COUNTER, CLEAR_AUG,
 * CLEAR_AUG_ISLAND, FIRE_HOOK). Follow-up commits will grow the set
 * with the real compute kernels (normalize / wind_bias / chebyshev /
 * divergence / …) keyed by numeric opcode ID.
 *
 * @maps_to PFSFTickPlanner.java — Java-side binary assembler.
 * @since v0.3d Phase 6
 */

#include "pfsf/pfsf_plan.h"
#include "pfsf/pfsf_extension.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>

namespace {

/* ═══ Test instrumentation ═══ */

std::atomic<int64_t> g_test_counter{0};

struct test_hook_key {
    int32_t island_id;
    int32_t point;
    bool operator==(const test_hook_key& o) const noexcept {
        return island_id == o.island_id && point == o.point;
    }
};
struct test_hook_hash {
    size_t operator()(const test_hook_key& k) const noexcept {
        return (static_cast<uint64_t>(k.island_id) << 32)
             ^ static_cast<uint32_t>(k.point);
    }
};

/* std::atomic is not move-constructible so it can't live directly in an
 * unordered_map (rehash requires moves). Box it in unique_ptr. */
struct test_hook_state {
    mutable std::shared_mutex                                               mtx;
    std::unordered_map<test_hook_key,
                       std::unique_ptr<std::atomic<int64_t>>,
                       test_hook_hash> counts;
};

test_hook_state& test_hooks() {
    static test_hook_state s;
    return s;
}

/* ═══ Little-endian safe readers ═══
 *
 * The plan format is defined as LE regardless of host byte order; we
 * read by byte to stay portable on any future BE runner and to avoid
 * UB from unaligned pointer casts. On x86/ARM the compiler folds
 * these into a single move. */

inline uint16_t read_u16_le(const uint8_t* p) noexcept {
    return static_cast<uint16_t>(p[0])
         | (static_cast<uint16_t>(p[1]) << 8);
}
inline uint32_t read_u32_le(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0])
         | (static_cast<uint32_t>(p[1]) << 8)
         | (static_cast<uint32_t>(p[2]) << 16)
         | (static_cast<uint32_t>(p[3]) << 24);
}
inline int32_t read_i32_le(const uint8_t* p) noexcept {
    return static_cast<int32_t>(read_u32_le(p));
}
inline int64_t read_i64_le(const uint8_t* p) noexcept {
    uint64_t lo = read_u32_le(p);
    uint64_t hi = read_u32_le(p + 4);
    return static_cast<int64_t>(lo | (hi << 32));
}

} /* namespace */

/* Test-hook callback — must have C linkage to be compatible with the
 * pfsf_hook_fn typedef. Defined outside the anonymous namespace so the
 * linkage attribute isn't fighting the namespace. */
extern "C" void pfsf_plan_internal_test_hook_cb(int32_t island_id,
                                                  int64_t /*epoch*/,
                                                  void*   ud) {
    const int32_t point = static_cast<int32_t>(reinterpret_cast<intptr_t>(ud));
    auto& s = test_hooks();
    std::shared_lock lk(s.mtx);
    auto it = s.counts.find({island_id, point});
    if (it != s.counts.end() && it->second) {
        it->second->fetch_add(1, std::memory_order_relaxed);
    }
}

/* ═══════════════════════════════════════════════════════════════════
 *  Public: plan execution
 * ═══════════════════════════════════════════════════════════════════ */

extern "C" pfsf_result pfsf_plan_execute(const void* plan,
                                           int64_t plan_bytes,
                                           pfsf_plan_result* out) {
    /* Normalise the result slot upfront so errors always have
     * somewhere to land. */
    if (out != nullptr) {
        out->struct_bytes     = static_cast<int32_t>(sizeof(*out));
        out->executed_count   = 0;
        out->failed_index     = -1;
        out->error_code       = PFSF_OK;
        out->hook_fire_count  = 0;
    }

    if (plan == nullptr || plan_bytes < PFSF_PLAN_HEADER_BYTES) {
        if (out) out->error_code = PFSF_ERROR_INVALID_ARG;
        return PFSF_ERROR_INVALID_ARG;
    }

    const uint8_t* base = static_cast<const uint8_t*>(plan);

    const uint32_t magic   = read_u32_le(base + 0);
    const uint16_t version = read_u16_le(base + 4);
    /* base+6 uint16 flags reserved */
    const int32_t  island  = read_i32_le(base + 8);
    const int32_t  opcount = read_i32_le(base + 12);

    if (magic != PFSF_PLAN_MAGIC
            || version != PFSF_PLAN_VERSION
            || opcount < 0) {
        if (out) out->error_code = PFSF_ERROR_INVALID_ARG;
        return PFSF_ERROR_INVALID_ARG;
    }

    int64_t cursor       = PFSF_PLAN_HEADER_BYTES;
    int32_t hook_fires   = 0;

    for (int32_t i = 0; i < opcount; ++i) {
        if (cursor + PFSF_PLAN_OP_HEADER_BYTES > plan_bytes) {
            if (out) {
                out->failed_index = i;
                out->error_code   = PFSF_ERROR_INVALID_ARG;
            }
            return PFSF_ERROR_INVALID_ARG;
        }

        const uint8_t* rec      = base + cursor;
        const uint16_t opcode   = read_u16_le(rec + 0);
        const uint16_t arg_len  = read_u16_le(rec + 2);
        const uint8_t* args     = rec + PFSF_PLAN_OP_HEADER_BYTES;

        if (cursor + PFSF_PLAN_OP_HEADER_BYTES + arg_len > plan_bytes) {
            if (out) {
                out->failed_index = i;
                out->error_code   = PFSF_ERROR_INVALID_ARG;
            }
            return PFSF_ERROR_INVALID_ARG;
        }

        switch (static_cast<pfsf_plan_opcode>(opcode)) {
            case PFSF_OP_NO_OP:
                break;

            case PFSF_OP_INCR_COUNTER: {
                if (arg_len < 4) {
                    if (out) {
                        out->failed_index = i;
                        out->error_code   = PFSF_ERROR_INVALID_ARG;
                    }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int32_t delta = read_i32_le(args);
                g_test_counter.fetch_add(delta, std::memory_order_relaxed);
                break;
            }

            case PFSF_OP_CLEAR_AUG: {
                if (arg_len < 4) {
                    if (out) {
                        out->failed_index = i;
                        out->error_code   = PFSF_ERROR_INVALID_ARG;
                    }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int32_t kind = read_i32_le(args);
                pfsf_aug_clear(island, static_cast<pfsf_augmentation_kind>(kind));
                break;
            }

            case PFSF_OP_CLEAR_AUG_ISLAND:
                pfsf_aug_clear_island(island);
                break;

            case PFSF_OP_FIRE_HOOK: {
                if (arg_len < 12) {
                    if (out) {
                        out->failed_index = i;
                        out->error_code   = PFSF_ERROR_INVALID_ARG;
                    }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int32_t point = read_i32_le(args);
                const int64_t epoch = read_i64_le(args + 4);
                hook_fires += pfsf_hook_fire(
                        island,
                        static_cast<pfsf_hook_point>(point),
                        epoch);
                break;
            }

            default:
                /* Unknown opcode — fail loudly so version drift surfaces. */
                if (out) {
                    out->failed_index = i;
                    out->error_code   = PFSF_ERROR_INVALID_ARG;
                }
                return PFSF_ERROR_INVALID_ARG;
        }

        cursor += PFSF_PLAN_OP_HEADER_BYTES + arg_len;
        if (out) out->executed_count += 1;
    }

    if (out) out->hook_fire_count = hook_fires;
    return PFSF_OK;
}

/* ═══════════════════════════════════════════════════════════════════
 *  Test-only helpers
 * ═══════════════════════════════════════════════════════════════════ */

extern "C" int64_t pfsf_plan_test_counter_read_reset(void) {
    return g_test_counter.exchange(0, std::memory_order_relaxed);
}

extern "C" void pfsf_plan_test_hook_install(int32_t island_id, int32_t point) {
    {
        auto& s = test_hooks();
        std::unique_lock lk(s.mtx);
        auto& slot = s.counts[{island_id, point}];
        if (!slot) slot = std::make_unique<std::atomic<int64_t>>(0);
        else       slot->store(0, std::memory_order_relaxed);
    }
    /* Wire the shared callback; user_data encodes the point so the
     * same fn can serve every (island, point) pair. */
    pfsf_hook_set(island_id,
                  static_cast<pfsf_hook_point>(point),
                  &pfsf_plan_internal_test_hook_cb,
                  reinterpret_cast<void*>(static_cast<intptr_t>(point)));
}

extern "C" int64_t pfsf_plan_test_hook_count_read_reset(int32_t island_id,
                                                         int32_t point) {
    auto& s = test_hooks();
    std::shared_lock lk(s.mtx);
    auto it = s.counts.find({island_id, point});
    if (it == s.counts.end() || !it->second) return 0;
    return it->second->exchange(0, std::memory_order_relaxed);
}
