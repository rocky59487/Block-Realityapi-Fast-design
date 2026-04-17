/**
 * @file features.cpp
 * @brief 12-dimensional solver feature vector for adaptive step-count /
 *        LOD / MG strategy regression.
 *
 * @cite IslandFeatureExtractor v1 — designed against
 *        Bungartz/Griebel (1998) "Sparse Grids" §7 (aspect-ratio and
 *        residual CV as LOD heuristics) +
 *        internal PFSF telemetry (damping/oscillation normalisation).
 * @maps_to IslandFeatureExtractor.java:extract() L45-L93 — bit-exact
 *          mirror.
 * @since v0.3d Phase 4
 */

#include "pfsf/pfsf_diagnostics.h"

#include <cmath>
#include <cstdint>

namespace {

constexpr float EPS              = 1e-20f;
constexpr int32_t OMEGA_NORM_CAP = 64;   /* PFSFScheduler.OMEGA_TABLE_SIZE */

inline float array_max(const float* arr, int32_t n) {
    if (arr == nullptr || n <= 0) return 0.0f;
    float m = arr[0];
    for (int32_t i = 1; i < n; ++i) {
        if (arr[i] > m) m = arr[i];
    }
    return m;
}

inline float coefficient_of_variation(const float* arr, int32_t n) {
    if (arr == nullptr || n < 2) return 0.0f;
    double sum = 0.0, sum2 = 0.0;
    for (int32_t i = 0; i < n; ++i) {
        const double v = static_cast<double>(arr[i]);
        sum  += v;
        sum2 += v * v;
    }
    const double mean = sum / static_cast<double>(n);
    if (mean < static_cast<double>(EPS)) return 0.0f;
    const double variance = sum2 / static_cast<double>(n) - mean * mean;
    return static_cast<float>(std::sqrt(std::max(0.0, variance)) / mean);
}

} /* namespace */

extern "C" void pfsf_extract_island_features(int32_t lx, int32_t ly, int32_t lz,
                                               int32_t chebyshev_iter,
                                               float   rho_spec_override,
                                               float   prev_max_macro_residual,
                                               int32_t oscillation_count,
                                               int32_t damping_active,
                                               int32_t stable_tick_count,
                                               int32_t lod_level,
                                               int32_t lod_dormant,
                                               int32_t pcg_allocated,
                                               const float* macro_residuals,
                                               int32_t macro_count,
                                               float* out12) {
    if (out12 == nullptr) return;

    const int64_t N = static_cast<int64_t>(lx > 0 ? lx : 0)
                    * static_cast<int64_t>(ly > 0 ? ly : 0)
                    * static_cast<int64_t>(lz > 0 ? lz : 0);
    const int64_t N_safe = N > 0 ? N : 1;

    /* [0] log2(N) */
    out12[0] = static_cast<float>(std::log(static_cast<double>(N_safe))
                                   / std::log(2.0));

    /* [1] aspect ratio maxDim / minDim */
    int32_t min_dim = lx;
    if (ly < min_dim) min_dim = ly;
    if (lz < min_dim) min_dim = lz;
    int32_t max_dim = lx;
    if (ly > max_dim) max_dim = ly;
    if (lz > max_dim) max_dim = lz;
    out12[1] = (min_dim > 0)
               ? static_cast<float>(max_dim) / static_cast<float>(min_dim)
               : 1.0f;

    /* [2] chebyshev_iter / 64 */
    out12[2] = static_cast<float>(chebyshev_iter) / static_cast<float>(OMEGA_NORM_CAP);

    /* [3] rho_spec_override */
    out12[3] = rho_spec_override;

    /* [4] log10(max(prev_max_macro_residual, EPS)) */
    const float clamped = prev_max_macro_residual > EPS
                          ? prev_max_macro_residual
                          : EPS;
    out12[4] = static_cast<float>(std::log10(static_cast<double>(clamped)));

    /* [5] residual drop rate (currentMax / prevMax, default 1 when
     *     there is no prior signal to compare against). */
    const float current_max = array_max(macro_residuals, macro_count);
    out12[5] = (prev_max_macro_residual > EPS)
               ? current_max / prev_max_macro_residual
               : 1.0f;

    /* [6] oscillation strength clamped to [0,1] */
    {
        const float v = static_cast<float>(oscillation_count) / 10.0f;
        out12[6] = v > 1.0f ? 1.0f : (v < 0.0f ? 0.0f : v);
    }

    /* [7] damping flag */
    out12[7] = damping_active != 0 ? 1.0f : 0.0f;

    /* [8] stability clamped to [0,1] */
    {
        const float v = static_cast<float>(stable_tick_count) / 100.0f;
        out12[8] = v > 1.0f ? 1.0f : (v < 0.0f ? 0.0f : v);
    }

    /* [9] macro-block CV (mean-centred std-dev) */
    out12[9] = coefficient_of_variation(macro_residuals, macro_count);

    /* [10] lod_level / max(lod_dormant, 1) */
    const int32_t dorm_denom = lod_dormant > 1 ? lod_dormant : 1;
    out12[10] = static_cast<float>(lod_level) / static_cast<float>(dorm_denom);

    /* [11] PCG allocated flag */
    out12[11] = pcg_allocated != 0 ? 1.0f : 0.0f;
}
