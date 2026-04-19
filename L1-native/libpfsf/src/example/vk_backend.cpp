#include "vk_backend.h"

#include <pfsf/pfsf.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <unordered_set>
#include <vector>

namespace pfsf_cli {

namespace {

/* Opaque context pointed to by pfsf_set_*_lookup callback user_data. */
struct LookupCtx {
    const Fixture*           fx       = nullptr;
    int32_t                  island_id = 1;
    /* Anchors hot-set for O(1) test — anchor_lookup is called once per
     * voxel per rebuild, so linear search over fx->anchors would be
     * quadratic on large fixtures. */
    std::unordered_set<int64_t> anchor_set;
};

inline int64_t pack_pos(int32_t x, int32_t y, int32_t z) {
    return (static_cast<int64_t>(x) & 0xFFFFFFFFL) |
           (static_cast<int64_t>(y) << 20)          |
           (static_cast<int64_t>(z) << 40);
}

inline int32_t idx3(const Fixture& fx, int32_t x, int32_t y, int32_t z) {
    return (z * fx.ly + y) * fx.lx + x;
}

pfsf_material vk_material_lookup(pfsf_pos pos, void* ud) {
    LookupCtx* ctx = static_cast<LookupCtx*>(ud);
    pfsf_material mat{};
    mat.density    = 2400.0f;
    mat.rcomp      = 30.0f;
    mat.rtens      = 3.0f;
    mat.youngs_gpa = 30.0f;
    mat.poisson    = 0.2f;
    mat.gc         = 100.0f;
    mat.is_anchor  = false;

    if (ctx == nullptr || ctx->fx == nullptr) return mat;
    const Fixture& fx = *ctx->fx;
    if (pos.x < 0 || pos.x >= fx.lx) return mat;
    if (pos.y < 0 || pos.y >= fx.ly) return mat;
    if (pos.z < 0 || pos.z >= fx.lz) return mat;

    int32_t id = fx.material_voxels[idx3(fx, pos.x, pos.y, pos.z)];
    if (id == 0) return mat;
    const FixtureMaterialEntry& m = lookup_material(fx, id);
    mat.density    = m.density;
    mat.rcomp      = m.rcomp;
    mat.rtens      = m.rtens;
    mat.youngs_gpa = m.youngs_gpa;
    mat.poisson    = m.poisson;
    mat.gc         = m.gc;
    mat.is_anchor  = m.is_anchor;
    return mat;
}

bool vk_anchor_lookup(pfsf_pos pos, void* ud) {
    LookupCtx* ctx = static_cast<LookupCtx*>(ud);
    if (ctx == nullptr) return false;
    return ctx->anchor_set.find(pack_pos(pos.x, pos.y, pos.z)) != ctx->anchor_set.end();
}

float vk_fill_ratio_lookup(pfsf_pos pos, void* ud) {
    LookupCtx* ctx = static_cast<LookupCtx*>(ud);
    if (ctx == nullptr || ctx->fx == nullptr) return 1.0f;
    const Fixture& fx = *ctx->fx;
    if (pos.x < 0 || pos.x >= fx.lx) return 0.0f;
    if (pos.y < 0 || pos.y >= fx.ly) return 0.0f;
    if (pos.z < 0 || pos.z >= fx.lz) return 0.0f;
    /* Solid voxels fully fill the cell; air is empty. Curing occupancy
     * rides on the curing layer, not fill ratio. */
    return (fx.material_voxels[idx3(fx, pos.x, pos.y, pos.z)] != 0) ? 1.0f : 0.0f;
}

float vk_curing_lookup(pfsf_pos pos, void* ud) {
    LookupCtx* ctx = static_cast<LookupCtx*>(ud);
    if (ctx == nullptr || ctx->fx == nullptr) return 1.0f;
    const Fixture& fx = *ctx->fx;
    if (fx.curing.empty()) return 1.0f;  /* fully cured if not tracked */
    if (pos.x < 0 || pos.x >= fx.lx) return 1.0f;
    if (pos.y < 0 || pos.y >= fx.ly) return 1.0f;
    if (pos.z < 0 || pos.z >= fx.lz) return 1.0f;
    return std::clamp(fx.curing[idx3(fx, pos.x, pos.y, pos.z)], 0.0f, 1.0f);
}

bool write_binary_floats(const std::string& path, const float* data, size_t n) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.write(reinterpret_cast<const char*>(data),
              static_cast<std::streamsize>(n * sizeof(float)));
    return out.good();
}

/** Newline-delimited JSON trace dump — one object per event. The schema
 *  mirrors the field order baked into pfsf_trace_event so tooling can
 *  match entries against the canonical {@code PFSF-CRASH} header layout. */
bool write_trace_json(const std::string& path,
                      const std::vector<pfsf_trace_event>& events) {
    std::ofstream out(path, std::ios::trunc);
    if (!out) return false;
    for (const auto& e : events) {
        char msg[40] = {};
        std::memcpy(msg, e.msg, sizeof(e.msg));
        msg[sizeof(e.msg)] = '\0';
        /* Escape backslashes + quotes in the msg field to keep the JSON
         * valid; everything else in pfsf_trace_event is numeric. */
        std::string esc;
        esc.reserve(std::strlen(msg) + 4);
        for (const char* p = msg; *p; ++p) {
            if (*p == '\\' || *p == '"') esc.push_back('\\');
            esc.push_back(*p);
        }
        out << "{\"epoch\":" << e.epoch
            << ",\"stage\":" << e.stage
            << ",\"island\":" << e.island_id
            << ",\"voxel\":" << e.voxel_index
            << ",\"errno\":" << e.errno_val
            << ",\"level\":" << e.level
            << ",\"msg\":\""  << esc << "\"}\n";
    }
    return out.good();
}

} /* namespace */

int run_vk(const Fixture& fx, const Args& args) {
    std::printf("libpfsf v%s — fixture=%s backend=vk\n",
                pfsf_version(), args.fixture_path.c_str());
    std::fflush(stdout);  /* keep banner + log output ordered vs stderr */

    pfsf_engine engine = pfsf_create(nullptr);
    if (!engine) {
        std::fprintf(stderr, "pfsf_cli: pfsf_create failed\n");
        return 1;
    }

    pfsf_result init_res = pfsf_init(engine);
    if (init_res != PFSF_OK) {
        std::fprintf(stderr,
                     "[pfsf_cli] pfsf_init returned %d (no Vulkan device?) — "
                     "skipping replay. Use --backend=cpu for a GPU-less run.\n",
                     init_res);
        pfsf_destroy(engine);
        return 0;  /* not a failure: CI runners without GPU hit this. */
    }

    LookupCtx ctx;
    ctx.fx = &fx;
    ctx.island_id = 1;
    for (const auto& a : fx.anchors) {
        ctx.anchor_set.insert(pack_pos(a[0], a[1], a[2]));
    }
    /* Any material entry flagged is_anchor also contributes. */
    for (int32_t z = 0; z < fx.lz; ++z)
    for (int32_t y = 0; y < fx.ly; ++y)
    for (int32_t x = 0; x < fx.lx; ++x) {
        int32_t id = fx.material_voxels[idx3(fx, x, y, z)];
        if (id == 0) continue;
        if (lookup_material(fx, id).is_anchor) {
            ctx.anchor_set.insert(pack_pos(x, y, z));
        }
    }

    pfsf_set_material_lookup(engine,  vk_material_lookup,    &ctx);
    pfsf_set_anchor_lookup(engine,    vk_anchor_lookup,      &ctx);
    pfsf_set_fill_ratio_lookup(engine, vk_fill_ratio_lookup, &ctx);
    pfsf_set_curing_lookup(engine,    vk_curing_lookup,      &ctx);

    const pfsf_vec3 wind{ fx.wind[0], fx.wind[1], fx.wind[2] };
    const float wind_mag = std::sqrt(wind.x * wind.x +
                                     wind.y * wind.y +
                                     wind.z * wind.z);
    if (wind_mag > 1e-6f) pfsf_set_wind(engine, &wind);

    pfsf_island_desc desc{};
    desc.island_id = ctx.island_id;
    desc.origin    = {0, 0, 0};
    desc.lx = fx.lx;
    desc.ly = fx.ly;
    desc.lz = fx.lz;

    pfsf_result res = pfsf_add_island(engine, &desc);
    if (res != PFSF_OK) {
        std::fprintf(stderr, "pfsf_cli: pfsf_add_island returned %d\n", res);
        pfsf_destroy(engine);
        return 1;
    }

    /* Run the requested number of ticks. After each tick we collect any
     * failure events and route the epoch counter forward. */
    std::vector<pfsf_failure_event> ev_storage(256);
    int32_t dirty = ctx.island_id;
    int64_t total_failures = 0;

    for (int32_t t = 0; t < args.ticks; ++t) {
        pfsf_tick_result tr{};
        tr.events   = ev_storage.data();
        tr.capacity = static_cast<int32_t>(ev_storage.size());
        res = pfsf_tick(engine, &dirty, /*dirty_count=*/1,
                        /*current_epoch=*/static_cast<int64_t>(t + 1),
                        &tr);
        if (res != PFSF_OK) {
            std::fprintf(stderr,
                         "[pfsf_cli] pfsf_tick returned %d at tick %d — aborting\n",
                         res, t);
            pfsf_remove_island(engine, ctx.island_id);
            pfsf_destroy(engine);
            return 1;
        }
        total_failures += tr.count;
        /* Only the first tick does a full rebuild; subsequent ones
         * piggy-back on the persistent buffers. */
        if (t == 0) dirty = ctx.island_id;
    }

    pfsf_stats stats{};
    pfsf_get_stats(engine, &stats);
    std::printf("  dims         = %dx%dx%d\n", fx.lx, fx.ly, fx.lz);
    std::printf("  ticks        = %d\n", args.ticks);
    std::printf("  failures     = %lld (summed across ticks)\n",
                static_cast<long long>(total_failures));
    std::printf("  islands      = %d, last tick = %.2f ms\n",
                stats.island_count, stats.last_tick_ms);

    /* Stress readback — the island's full stress field (float32[N]). */
    if (!args.dump_stress.empty()) {
        const size_t N = static_cast<size_t>(fx.lx) *
                         static_cast<size_t>(fx.ly) *
                         static_cast<size_t>(fx.lz);
        std::vector<float> stress(N, 0.0f);
        int32_t got = 0;
        pfsf_result rr = pfsf_read_stress(engine, ctx.island_id,
                                          stress.data(),
                                          static_cast<int32_t>(N), &got);
        if (rr != PFSF_OK || got < 0) {
            std::fprintf(stderr, "pfsf_cli: pfsf_read_stress returned %d\n", rr);
        } else {
            if (static_cast<size_t>(got) < N) stress.resize(static_cast<size_t>(got));
            if (!write_binary_floats(args.dump_stress, stress.data(), stress.size())) {
                std::fprintf(stderr, "pfsf_cli: cannot write stress dump: %s\n",
                             args.dump_stress.c_str());
            } else {
                std::printf("  dump-stress  = %s (%d floats)\n",
                            args.dump_stress.c_str(), got);
            }
        }
    }

    /* Trace drain — newline-delimited JSON events. */
    if (!args.dump_trace.empty()) {
        std::vector<pfsf_trace_event> drained(PFSF_TRACE_RING_CAPACITY);
        int32_t got = pfsf_drain_trace(engine, drained.data(),
                                       static_cast<int32_t>(drained.size()));
        if (got < 0) {
            std::fprintf(stderr, "pfsf_cli: pfsf_drain_trace returned %d\n", got);
        } else {
            drained.resize(static_cast<size_t>(got));
            if (!write_trace_json(args.dump_trace, drained)) {
                std::fprintf(stderr, "pfsf_cli: cannot write trace dump: %s\n",
                             args.dump_trace.c_str());
            } else {
                std::printf("  dump-trace   = %s (%d events)\n",
                            args.dump_trace.c_str(), got);
            }
        }
    }

    pfsf_remove_island(engine, ctx.island_id);
    pfsf_destroy(engine);
    return 0;
}

} /* namespace pfsf_cli */
