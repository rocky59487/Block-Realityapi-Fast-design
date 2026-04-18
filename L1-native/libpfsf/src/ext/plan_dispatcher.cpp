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
#include "pfsf/pfsf_compute.h"
#include "pfsf/pfsf_diagnostics.h"
#include "pfsf/pfsf_extension.h"
#include "pfsf/pfsf_trace.h"

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
inline float read_f32_le(const uint8_t* p) noexcept {
    const uint32_t bits = read_u32_le(p);
    float f;
    std::memcpy(&f, &bits, sizeof(f));
    return f;
}

/* A plan-encoded int64 is treated as an opaque pointer. Zero means
 * "not supplied"; callers interpret per-op whether that is legal. */
template <typename T>
inline T* ptr_from_i64(int64_t addr) noexcept {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(addr));
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
        pfsf_trace_emit(PFSF_TRACE_ERROR, 0, -1, island, -1,
                        PFSF_ERROR_INVALID_ARG, "plan: bad header");
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

            /* ─── v0.3e M2 compute opcodes ────────────────────────
             *
             * Every handler below has the same shape:
             *   1. Enforce a minimum arg_len — forward-compat tails
             *      beyond the known prefix are allowed and ignored.
             *   2. Reject a zero output address (would deref null).
             *   3. Call the stateless primitive from pfsf_compute.h or
             *      pfsf_diagnostics.h with raw addresses.
             *
             * On any malformed record we set failed_index/error_code
             * exactly like the header-era handlers above and bail out
             * so Java can surface the culprit opcode index. */

            case PFSF_OP_NORMALIZE_SOA6: {
                if (arg_len < 52) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t src_a   = read_i64_le(args +  0);
                const int64_t rc_a    = read_i64_le(args +  8);
                const int64_t rt_a    = read_i64_le(args + 16);
                const int64_t cond_a  = read_i64_le(args + 24);
                const int64_t hyd_a   = read_i64_le(args + 32);
                const int64_t sig_a   = read_i64_le(args + 40);
                const int32_t n       = read_i32_le(args + 48);
                if (src_a == 0 || rc_a == 0 || rt_a == 0
                        || cond_a == 0 || sig_a == 0 || n < 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_normalize_soa6(
                        ptr_from_i64<float>(src_a),
                        ptr_from_i64<float>(rc_a),
                        ptr_from_i64<float>(rt_a),
                        ptr_from_i64<float>(cond_a),
                        ptr_from_i64<const float>(hyd_a),    /* may be nullptr */
                        n,
                        ptr_from_i64<float>(sig_a));
                break;
            }

            case PFSF_OP_APPLY_WIND_BIAS: {
                if (arg_len < 32) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t cond_a = read_i64_le(args +  0);
                const int32_t n      = read_i32_le(args +  8);
                /* args+12 _pad */
                pfsf_vec3 wind = { read_f32_le(args + 16),
                                   read_f32_le(args + 20),
                                   read_f32_le(args + 24) };
                const float up = read_f32_le(args + 28);
                if (cond_a == 0 || n < 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_apply_wind_bias(ptr_from_i64<float>(cond_a),
                                      n, wind, up);
                break;
            }

            case PFSF_OP_COMPUTE_CONDUCTIVITY: {
                if (arg_len < 64) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t cond_a = read_i64_le(args +  0);
                const int64_t rc_a   = read_i64_le(args +  8);
                const int64_t rt_a   = read_i64_le(args + 16);
                const int64_t type_a = read_i64_le(args + 24);
                const int32_t lx     = read_i32_le(args + 32);
                const int32_t ly     = read_i32_le(args + 36);
                const int32_t lz     = read_i32_le(args + 40);
                /* args+44 _pad */
                pfsf_vec3 wind = { read_f32_le(args + 48),
                                   read_f32_le(args + 52),
                                   read_f32_le(args + 56) };
                const float up = read_f32_le(args + 60);
                if (cond_a == 0 || rc_a == 0 || rt_a == 0 || type_a == 0
                        || lx <= 0 || ly <= 0 || lz <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_compute_conductivity(
                        ptr_from_i64<float>(cond_a),
                        ptr_from_i64<const float>(rc_a),
                        ptr_from_i64<const float>(rt_a),
                        ptr_from_i64<const uint8_t>(type_a),
                        lx, ly, lz, wind, up);
                break;
            }

            case PFSF_OP_ARM_MAP: {
                if (arg_len < 40) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t mem_a  = read_i64_le(args +  0);
                const int64_t anc_a  = read_i64_le(args +  8);
                const int64_t out_a  = read_i64_le(args + 16);
                const int32_t lx     = read_i32_le(args + 24);
                const int32_t ly     = read_i32_le(args + 28);
                const int32_t lz     = read_i32_le(args + 32);
                if (mem_a == 0 || anc_a == 0 || out_a == 0
                        || lx <= 0 || ly <= 0 || lz <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const pfsf_result rc = pfsf_compute_arm_map(
                        ptr_from_i64<const uint8_t>(mem_a),
                        ptr_from_i64<const uint8_t>(anc_a),
                        lx, ly, lz,
                        ptr_from_i64<int32_t>(out_a));
                if (rc != PFSF_OK) {
                    if (out) { out->failed_index = i;
                               out->error_code = rc; }
                    return rc;
                }
                break;
            }

            case PFSF_OP_ARCH_FACTOR: {
                if (arg_len < 40) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t mem_a  = read_i64_le(args +  0);
                const int64_t anc_a  = read_i64_le(args +  8);
                const int64_t out_a  = read_i64_le(args + 16);
                const int32_t lx     = read_i32_le(args + 24);
                const int32_t ly     = read_i32_le(args + 28);
                const int32_t lz     = read_i32_le(args + 32);
                if (mem_a == 0 || anc_a == 0 || out_a == 0
                        || lx <= 0 || ly <= 0 || lz <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const pfsf_result rc = pfsf_compute_arch_factor_map(
                        ptr_from_i64<const uint8_t>(mem_a),
                        ptr_from_i64<const uint8_t>(anc_a),
                        lx, ly, lz,
                        ptr_from_i64<float>(out_a));
                if (rc != PFSF_OK) {
                    if (out) { out->failed_index = i;
                               out->error_code = rc; }
                    return rc;
                }
                break;
            }

            case PFSF_OP_PHANTOM_EDGES: {
                if (arg_len < 56) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t mem_a  = read_i64_le(args +  0);
                const int64_t cond_a = read_i64_le(args +  8);
                const int64_t rc_a   = read_i64_le(args + 16);
                const int64_t inj_a  = read_i64_le(args + 24);
                const int32_t lx     = read_i32_le(args + 32);
                const int32_t ly     = read_i32_le(args + 36);
                const int32_t lz     = read_i32_le(args + 40);
                /* args+44 _pad */
                const float edge     = read_f32_le(args + 48);
                const float corner   = read_f32_le(args + 52);
                if (mem_a == 0 || cond_a == 0 || rc_a == 0
                        || lx <= 0 || ly <= 0 || lz <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int32_t injected = pfsf_inject_phantom_edges(
                        ptr_from_i64<const uint8_t>(mem_a),
                        ptr_from_i64<float>(cond_a),
                        ptr_from_i64<const float>(rc_a),
                        lx, ly, lz, edge, corner);
                if (inj_a != 0) {
                    *ptr_from_i64<int32_t>(inj_a) = injected;
                }
                break;
            }

            case PFSF_OP_DOWNSAMPLE_2TO1: {
                if (arg_len < 48) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t fine_a  = read_i64_le(args +  0);
                const int64_t ftyp_a  = read_i64_le(args +  8);
                const int64_t crs_a   = read_i64_le(args + 16);
                const int64_t ctyp_a  = read_i64_le(args + 24);
                const int32_t lxf     = read_i32_le(args + 32);
                const int32_t lyf     = read_i32_le(args + 36);
                const int32_t lzf     = read_i32_le(args + 40);
                if (fine_a == 0 || ftyp_a == 0 || crs_a == 0 || ctyp_a == 0
                        || lxf <= 0 || lyf <= 0 || lzf <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_downsample_2to1(
                        ptr_from_i64<const float>(fine_a),
                        ptr_from_i64<const uint8_t>(ftyp_a),
                        lxf, lyf, lzf,
                        ptr_from_i64<float>(crs_a),
                        ptr_from_i64<uint8_t>(ctyp_a));
                break;
            }

            case PFSF_OP_TILED_LAYOUT: {
                if (arg_len < 32) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t lin_a = read_i64_le(args +  0);
                const int64_t out_a = read_i64_le(args +  8);
                const int32_t lx    = read_i32_le(args + 16);
                const int32_t ly    = read_i32_le(args + 20);
                const int32_t lz    = read_i32_le(args + 24);
                const int32_t tile  = read_i32_le(args + 28);
                if (lin_a == 0 || out_a == 0
                        || lx <= 0 || ly <= 0 || lz <= 0 || tile <= 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_tiled_layout_build(
                        ptr_from_i64<const float>(lin_a),
                        lx, ly, lz, tile,
                        ptr_from_i64<float>(out_a));
                break;
            }

            case PFSF_OP_CHEBYSHEV: {
                if (arg_len < 20) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t out_a = read_i64_le(args +  0);
                const int32_t iter  = read_i32_le(args +  8);
                /* args+12 _pad */
                const float rho     = read_f32_le(args + 16);
                if (out_a == 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                *ptr_from_i64<float>(out_a) = pfsf_chebyshev_omega(iter, rho);
                break;
            }

            case PFSF_OP_CHECK_DIVERGENCE: {
                if (arg_len < 40) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t st_a    = read_i64_le(args +  0);
                const int64_t macro_a = read_i64_le(args +  8);
                const int64_t kind_a  = read_i64_le(args + 16);
                const float max_phi   = read_f32_le(args + 24);
                const int32_t mcount  = read_i32_le(args + 28);
                const float ratio     = read_f32_le(args + 32);
                const float settle    = read_f32_le(args + 36);
                if (st_a == 0 || kind_a == 0 || mcount < 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int32_t kind = pfsf_check_divergence(
                        ptr_from_i64<pfsf_divergence_state>(st_a),
                        max_phi,
                        ptr_from_i64<const float>(macro_a),
                        mcount, ratio, settle);
                *ptr_from_i64<int32_t>(kind_a) = kind;
                break;
            }

            case PFSF_OP_EXTRACT_FEATURES: {
                if (arg_len < 72) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t res_a   = read_i64_le(args +  0);
                const int64_t out_a   = read_i64_le(args +  8);
                const int32_t lx      = read_i32_le(args + 16);
                const int32_t ly      = read_i32_le(args + 20);
                const int32_t lz      = read_i32_le(args + 24);
                const int32_t cheby   = read_i32_le(args + 28);
                const int32_t osc     = read_i32_le(args + 32);
                const int32_t damping = read_i32_le(args + 36);
                const int32_t stable  = read_i32_le(args + 40);
                const int32_t lod     = read_i32_le(args + 44);
                const int32_t dormant = read_i32_le(args + 48);
                const int32_t pcg     = read_i32_le(args + 52);
                const int32_t mcount  = read_i32_le(args + 56);
                /* args+60 _pad */
                const float rho_ovr   = read_f32_le(args + 64);
                const float prev_res  = read_f32_le(args + 68);
                if (out_a == 0 || lx <= 0 || ly <= 0 || lz <= 0 || mcount < 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                pfsf_extract_island_features(
                        lx, ly, lz, cheby, rho_ovr, prev_res,
                        osc, damping, stable, lod, dormant, pcg,
                        ptr_from_i64<const float>(res_a), mcount,
                        ptr_from_i64<float>(out_a));
                break;
            }

            case PFSF_OP_WIND_PRESSURE: {
                if (arg_len < 24) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t out_a = read_i64_le(args +  0);
                const float speed   = read_f32_le(args +  8);
                const float density = read_f32_le(args + 12);
                const int32_t exposed = read_i32_le(args + 16);
                /* args+20 reserved (padding / future Cp override) */
                if (out_a == 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                *ptr_from_i64<float>(out_a) = pfsf_wind_pressure_source(
                        speed, density, exposed != 0);
                break;
            }

            case PFSF_OP_TIMOSHENKO: {
                if (arg_len < 28) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                const int64_t out_a   = read_i64_le(args +  0);
                const float b         = read_f32_le(args +  8);
                const float h         = read_f32_le(args + 12);
                const int32_t arm     = read_i32_le(args + 16);
                const float youngs    = read_f32_le(args + 20);
                const float nu        = read_f32_le(args + 24);
                if (out_a == 0) {
                    if (out) { out->failed_index = i;
                               out->error_code = PFSF_ERROR_INVALID_ARG; }
                    return PFSF_ERROR_INVALID_ARG;
                }
                *ptr_from_i64<float>(out_a) = pfsf_timoshenko_moment_factor(
                        b, h, arm, youngs, nu);
                break;
            }

            default:
                /* Unknown opcode — fail loudly so version drift surfaces. */
                if (out) {
                    out->failed_index = i;
                    out->error_code   = PFSF_ERROR_INVALID_ARG;
                }
                pfsf_trace_emit(PFSF_TRACE_ERROR, 0, -1, island, i,
                                PFSF_ERROR_INVALID_ARG,
                                "plan: unknown opcode");
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
