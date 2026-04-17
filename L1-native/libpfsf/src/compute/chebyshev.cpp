/**
 * @file chebyshev.cpp
 * @brief Wang 2015 Chebyshev semi-iteration + spectral radius + step
 *        recommender + macro-block hysteresis.
 *
 * @cite Wang, H. (2015). "A Chebyshev Semi-Iterative Approach for
 *        Accelerating Projective and Position-based Dynamics".
 *        ACM TOG (SIGGRAPH Asia) 34(6):246, §4 Eq.(12).
 * @cite Briggs, W.L., Henson, V.E., McCormick, S.F. (2000).
 *        "A Multigrid Tutorial", 2nd ed., SIAM, §3.
 * @maps_to PFSFScheduler.java (computeOmega / precomputeOmegaTable /
 *          estimateSpectralRadius / recommendSteps /
 *          isMacroBlockActive / getActiveRatio) — bit-exact mirror of
 *          the L45-L397 window.
 * @since v0.3d Phase 4
 */

#include "pfsf/pfsf_diagnostics.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace {

/* Shared recurrence used by both the single-omega accessor and the
 * full-table precompute. Matches PFSFScheduler.computeOmega's A6-fix
 * branch ordering (fall back to omega_prev on denominator near zero
 * OR on out-of-range / NaN / >2 results). */
inline float chebyshev_recurrence(int32_t iter, float rho_spec) {
    if (iter <= 0) return 1.0f;

    const float rho_sq = rho_spec * rho_spec;
    if (rho_sq >= 1.0f) return 1.0f;

    if (iter == 1) {
        return 2.0f / (2.0f - rho_sq);
    }

    float omega_prev = 1.0f;
    float omega      = 2.0f / (2.0f - rho_sq);
    for (int32_t k = 2; k <= iter; ++k) {
        const float denom = 4.0f - rho_sq * omega;
        if (denom < PFSF_OMEGA_DENOM_EPSILON) {
            omega = omega_prev;
            break;
        }
        const float omega_new = 4.0f / denom;
        if (omega_new > 2.0f || std::isnan(omega_new)) {
            omega = omega_prev;
            break;
        }
        omega_prev = omega;
        omega      = omega_new;
    }
    return std::min(omega, static_cast<float>(PFSF_MAX_OMEGA));
}

} /* namespace */

extern "C" float pfsf_chebyshev_omega(int32_t iter, float rho_spec) {
    return chebyshev_recurrence(iter, rho_spec);
}

extern "C" int32_t pfsf_precompute_omega_table(float rho_spec,
                                                 float* out,
                                                 int32_t capacity) {
    if (out == nullptr || capacity <= 0) return PFSF_ERROR_INVALID_ARG;

    const float rho_sq = rho_spec * rho_spec;
    out[0] = 1.0f;
    if (capacity > 1) {
        /* When rho_sq >= 1 the Chebyshev recurrence does not converge
         * — mirror Java's behaviour of quietly returning omega=1 in
         * that regime (the per-iter accessor already guards against
         * this; here we keep the whole table at 1). */
        out[1] = (rho_sq < 1.0f)
                 ? 2.0f / (2.0f - rho_sq)
                 : 1.0f;
    }
    for (int32_t k = 2; k < capacity; ++k) {
        const float denom = 4.0f - rho_sq * out[k - 1];
        if (denom < PFSF_OMEGA_DENOM_EPSILON) {
            out[k] = out[k - 1];
            continue;
        }
        float w = 4.0f / denom;
        if (w > static_cast<float>(PFSF_MAX_OMEGA)) w = static_cast<float>(PFSF_MAX_OMEGA);
        if (std::isnan(w)) w = 1.0f;
        out[k] = w;
    }
    return capacity;
}

extern "C" float pfsf_estimate_spectral_radius(int32_t l_max,
                                                 float safety_margin) {
    if (l_max <= 1) return 0.5f;
    const double pi = 3.14159265358979323846;
    return static_cast<float>(std::cos(pi / static_cast<double>(l_max))
                              * static_cast<double>(safety_margin));
}

extern "C" int32_t pfsf_recommend_steps(int32_t ly,
                                          int32_t cheby_iter,
                                          int32_t is_dirty,
                                          int32_t has_collapse,
                                          int32_t steps_minor,
                                          int32_t steps_major,
                                          int32_t steps_collapse) {
    if (!is_dirty && cheby_iter > PFSF_OMEGA_TABLE_SIZE) {
        return 0;
    }
    if (has_collapse) {
        /* height * 1.5 matches the Java integer truncation
         * (int) (height * 1.5). */
        int32_t dynamic = static_cast<int32_t>(static_cast<float>(ly) * 1.5f);
        if (dynamic < steps_collapse) dynamic = steps_collapse;
        if (dynamic > 128)            dynamic = 128;
        return dynamic;
    }
    return is_dirty ? steps_major : steps_minor;
}

extern "C" int32_t pfsf_macro_block_active(float residual,
                                             int32_t was_active) {
    if (was_active != 0) {
        return residual > PFSF_MACRO_DEACTIVATE_THRESHOLD ? 1 : 0;
    }
    return residual > PFSF_MACRO_ACTIVATE_THRESHOLD ? 1 : 0;
}

extern "C" float pfsf_macro_active_ratio(const float* residuals,
                                           int32_t n,
                                           const uint8_t* was_active) {
    if (residuals == nullptr || n <= 0) return 1.0f;
    int32_t active = 0;
    for (int32_t i = 0; i < n; ++i) {
        int32_t prev = (was_active != nullptr) ? (was_active[i] != 0 ? 1 : 0) : 1;
        if (pfsf_macro_block_active(residuals[i], prev)) ++active;
    }
    return static_cast<float>(active) / static_cast<float>(n);
}
