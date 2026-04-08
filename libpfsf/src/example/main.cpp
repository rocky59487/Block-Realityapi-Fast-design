/**
 * @file main.cpp
 * @brief Standalone CLI example for libpfsf.
 *
 * Usage: pfsf_cli
 *   Creates an engine, adds a test island, runs one tick, prints stats.
 *   Useful for verifying Vulkan init + buffer allocation without Java.
 */
#include <pfsf/pfsf.h>
#include <cstdio>
#include <cstdlib>

static pfsf_material test_material_lookup(pfsf_pos /*pos*/, void* /*ud*/) {
    pfsf_material mat{};
    mat.density   = 2400.0f;   // concrete kg/m³
    mat.rcomp     = 30.0f;     // 30 MPa
    mat.rtens     = 3.0f;      // 3 MPa
    mat.youngs_gpa = 30.0f;    // 30 GPa
    mat.poisson   = 0.2f;
    mat.gc        = 100.0f;    // J/m²
    mat.is_anchor = false;
    return mat;
}

static bool test_anchor_lookup(pfsf_pos pos, void* /*ud*/) {
    return pos.y == 0;  // ground level is anchor
}

static float test_fill_ratio(pfsf_pos /*pos*/, void* /*ud*/) {
    return 1.0f;
}

int main() {
    printf("libpfsf v%s — standalone test\n", pfsf_version());
    printf("─────────────────────────────\n");

    // Create with defaults
    pfsf_engine engine = pfsf_create(nullptr);
    if (!engine) {
        fprintf(stderr, "pfsf_create failed\n");
        return 1;
    }

    // Initialize Vulkan
    pfsf_result res = pfsf_init(engine);
    if (res != PFSF_OK) {
        printf("pfsf_init: error %d (no GPU? expected in CI)\n", res);
        pfsf_destroy(engine);
        return 0;  // not a failure in headless
    }

    printf("Engine available: %s\n", pfsf_is_available(engine) ? "yes" : "no");

    // Set callbacks
    pfsf_set_material_lookup(engine, test_material_lookup, nullptr);
    pfsf_set_anchor_lookup(engine, test_anchor_lookup, nullptr);
    pfsf_set_fill_ratio_lookup(engine, test_fill_ratio, nullptr);

    // Add a 16×16×16 test island
    pfsf_island_desc desc{};
    desc.island_id = 1;
    desc.origin    = {0, 0, 0};
    desc.lx = 16;
    desc.ly = 16;
    desc.lz = 16;

    res = pfsf_add_island(engine, &desc);
    printf("pfsf_add_island: %s\n", res == PFSF_OK ? "OK" : "FAILED");

    // Run one tick
    int32_t dirty[] = {1};
    pfsf_failure_event events[64];
    pfsf_tick_result tick_result{};
    tick_result.events   = events;
    tick_result.capacity = 64;

    res = pfsf_tick(engine, dirty, 1, 1, &tick_result);
    printf("pfsf_tick: %s (failures: %d)\n",
           res == PFSF_OK ? "OK" : "FAILED", tick_result.count);

    // Stats
    pfsf_stats stats{};
    pfsf_get_stats(engine, &stats);
    printf("Stats: %d islands, %lld voxels, %.2f ms/tick\n",
           stats.island_count, (long long)stats.total_voxels, stats.last_tick_ms);

    // Cleanup
    pfsf_remove_island(engine, 1);
    pfsf_destroy(engine);
    printf("Done.\n");
    return 0;
}
