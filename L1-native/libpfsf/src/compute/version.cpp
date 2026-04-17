/**
 * @file version.cpp
 * @brief Real implementations of pfsf_version.h.
 *
 * Phase 1 lit up "compute.v1" (normalize_soa6 / apply_wind_bias /
 * timoshenko_moment_factor / wind_pressure_source). Phase 2 adds
 * "compute.v2" covering the topology primitives: compute_arm_map,
 * compute_arch_factor_map, inject_phantom_edges.
 *
 * ABI version policy (v0.3d):
 *   MAJOR — bumped on any breaking struct/enum/symbol change.
 *   MINOR — bumped each phase (Phase 0 == 0, Phase 1 == 1, …).
 *   PATCH — bumped on additive fixes within a phase.
 */

#include "pfsf/pfsf_version.h"

#include <cstring>

namespace {
constexpr uint32_t ABI_MAJOR = 0;
constexpr uint32_t ABI_MINOR = 6;      /* Phase 6 (plan buffer: opcode dispatcher + test hooks) */
constexpr uint32_t ABI_PATCH = 0;

constexpr const char* BUILD_INFO =
    "libpfsf_compute v0.3d-phase6 (abi="
#if defined(__AVX512F__)
    "avx512"
#elif defined(__AVX2__)
    "avx2"
#elif defined(__ARM_NEON)
    "neon"
#else
    "scalar"
#endif
    ")";

struct FeatureEntry {
    const char* name;
    bool        enabled;
};

/* Ordering matters only for `pfsf_build_info`-style diagnostics;
 * lookup is by name. */
constexpr FeatureEntry FEATURES[] = {
    { "compute.v1",           true  },  /* Phase 1 surface live */
    { "compute.v2",           true  },  /* Phase 2: arm/arch/phantom */
    { "compute.v3",           true  },  /* Phase 3: morton/downsample/tiled_layout */
    { "compute.v4",           true  },  /* Phase 4: chebyshev/spectral/divergence/features */
    { "diagnostics.v1",       true  },  /* Phase 4: alias for compute.v4 */
    { "compute.v5",           true  },  /* Phase 5: augmentation registry + hook table */
    { "extension.v1",         true  },  /* Phase 5: alias for compute.v5 */
    { "compute.v6",           true  },  /* Phase 6: plan buffer dispatcher */
    { "plan.v1",              true  },  /* Phase 6: alias for compute.v6 */
    { "plan_buffer",          true  },  /* Phase 6: legacy flag alias */
    { "trace.ring",           false },  /* Phase 7 */
#if defined(__AVX512F__)
    { "simd.avx512",          true  },
    { "simd.avx2",            true  },
#elif defined(__AVX2__)
    { "simd.avx512",          false },
    { "simd.avx2",            true  },
#elif defined(__ARM_NEON)
    { "simd.neon",            true  },
#else
    { "simd.avx2",            false },
    { "simd.neon",            false },
#endif
};
} /* namespace */

extern "C" uint32_t pfsf_abi_version(void) {
    return (ABI_MAJOR << 16) | (ABI_MINOR << 8) | ABI_PATCH;
}

extern "C" bool pfsf_has_feature(const char* name) {
    if (name == nullptr) return false;
    for (const auto& e : FEATURES) {
        if (std::strcmp(e.name, name) == 0) return e.enabled;
    }
    return false;
}

extern "C" const char* pfsf_build_info(void) {
    return BUILD_INFO;
}
