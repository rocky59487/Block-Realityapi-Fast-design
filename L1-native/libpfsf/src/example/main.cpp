/**
 * @file main.cpp
 * @brief Standalone CLI for libpfsf.
 *
 * v0.4 M3a — the one-shot smoke test is preserved as `--backend=smoke`
 * (also the default when no `--fixture` is named) so existing CI jobs
 * keep working. New flags add a fixture-replay mode driven by a schema-v1
 * JSON file; the CPU and VK backends land in M3b/M3c respectively.
 *
 * Usage (full surface): see pfsf_cli_args.cpp::print_usage().
 */
#include "cpu_backend.h"
#include "fixture_loader.h"
#include "pfsf_cli_args.h"

#include <pfsf/pfsf.h>

#include <cstdio>
#include <cstdlib>

namespace {

pfsf_material smoke_material_lookup(pfsf_pos /*pos*/, void* /*ud*/) {
    pfsf_material mat{};
    mat.density    = 2400.0f;
    mat.rcomp      = 30.0f;
    mat.rtens      = 3.0f;
    mat.youngs_gpa = 30.0f;
    mat.poisson    = 0.2f;
    mat.gc         = 100.0f;
    mat.is_anchor  = false;
    return mat;
}

bool smoke_anchor_lookup(pfsf_pos pos, void* /*ud*/) {
    return pos.y == 0;
}

float smoke_fill_ratio(pfsf_pos /*pos*/, void* /*ud*/) {
    return 1.0f;
}

/** Pre-v0.4 behaviour: init, add island, tick once, print stats. */
int run_smoke() {
    std::printf("libpfsf v%s — standalone test (smoke)\n", pfsf_version());
    std::printf("──────────────────────────────────────\n");

    pfsf_engine engine = pfsf_create(nullptr);
    if (!engine) {
        std::fprintf(stderr, "pfsf_create failed\n");
        return 1;
    }

    pfsf_result res = pfsf_init(engine);
    if (res != PFSF_OK) {
        std::printf("pfsf_init: error %d (no GPU? expected in CI)\n", res);
        pfsf_destroy(engine);
        return 0;
    }

    std::printf("Engine available: %s\n", pfsf_is_available(engine) ? "yes" : "no");

    pfsf_set_material_lookup(engine, smoke_material_lookup, nullptr);
    pfsf_set_anchor_lookup(engine, smoke_anchor_lookup, nullptr);
    pfsf_set_fill_ratio_lookup(engine, smoke_fill_ratio, nullptr);

    pfsf_island_desc desc{};
    desc.island_id = 1;
    desc.origin    = {0, 0, 0};
    desc.lx = 16;
    desc.ly = 16;
    desc.lz = 16;

    res = pfsf_add_island(engine, &desc);
    std::printf("pfsf_add_island: %s\n", res == PFSF_OK ? "OK" : "FAILED");

    int32_t dirty[] = {1};
    pfsf_failure_event events[64];
    pfsf_tick_result tick_result{};
    tick_result.events   = events;
    tick_result.capacity = 64;

    res = pfsf_tick(engine, dirty, 1, 1, &tick_result);
    std::printf("pfsf_tick: %s (failures: %d)\n",
                res == PFSF_OK ? "OK" : "FAILED", tick_result.count);

    pfsf_stats stats{};
    pfsf_get_stats(engine, &stats);
    std::printf("Stats: %d islands, %lld voxels, %.2f ms/tick\n",
                stats.island_count, (long long)stats.total_voxels, stats.last_tick_ms);

    pfsf_remove_island(engine, 1);
    pfsf_destroy(engine);
    std::printf("Done.\n");
    return 0;
}

/** VK backend stub — full pfsf_tick path + stress dump; M3c. */
int run_vk_stub(const pfsf_cli::Fixture& fx, const pfsf_cli::Args& args) {
    std::printf("libpfsf v%s — fixture=%s backend=vk (stub, M3c)\n",
                pfsf_version(),
                args.fixture_path.c_str());
    std::printf("  dims    = %dx%dx%d\n", fx.lx, fx.ly, fx.lz);
    std::printf("  ticks   = %d\n", args.ticks);
    std::fprintf(stderr,
                 "[pfsf_cli] --backend=vk is parsed but not yet dispatched.\n"
                 "          full replay + stress dump lands in v0.4 M3c.\n");
    return 0;
}

} /* namespace */

int main(int argc, char** argv) {
    pfsf_cli::Args args = pfsf_cli::parse(argc, argv);
    if (!args.error.empty()) {
        std::fprintf(stderr, "pfsf_cli: %s\n", args.error.c_str());
        pfsf_cli::print_usage();
        return 2;
    }
    if (args.want_help) {
        pfsf_cli::print_usage();
        return 0;
    }

    if (args.backend == pfsf_cli::Backend::SMOKE && args.fixture_path.empty()) {
        return run_smoke();
    }
    if (args.fixture_path.empty()) {
        std::fprintf(stderr,
                     "pfsf_cli: --backend=cpu/vk requires --fixture <path>\n");
        return 2;
    }

    pfsf_cli::LoadResult fxr = pfsf_cli::load_fixture(args.fixture_path);
    if (!fxr.ok) {
        std::fprintf(stderr, "pfsf_cli: %s\n", fxr.error.c_str());
        return 1;
    }

    switch (args.backend) {
        case pfsf_cli::Backend::CPU:   return pfsf_cli::run_cpu(fxr.fixture, args);
        case pfsf_cli::Backend::VK:    return run_vk_stub(fxr.fixture, args);
        case pfsf_cli::Backend::SMOKE:
            /* `--fixture` with no explicit backend already gets mapped to VK
             * by the parser; an explicit `--backend=smoke --fixture=...`
             * deliberately ignores the fixture. */
            return run_smoke();
    }
    return 0;
}
