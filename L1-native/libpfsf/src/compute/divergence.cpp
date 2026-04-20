/**
 * @file divergence.cpp
 * @brief NaN/Inf / rapid growth / oscillation / macro-region divergence
 *        guard — state-machine companion of {@code pfsf_check_divergence}.
 *
 * @cite Internal PFSF stability guard — composition of:
 *        Strang/Fix (1973) "Analysis of the Finite Element Method"
 *        §2.3 (spectral growth bounds) +
 *        PFSF handbook §4.3.2 (oscillation heuristics + macro-region
 *        divergence hysteresis).
 * @maps_to PFSFScheduler.java:checkDivergence() L209-L315 — bit-exact
 *          mirror of the state mutations. Logging and island-id echo
 *          stay in Java.
 * @since v0.3d Phase 4
 */

#include "pfsf/pfsf_diagnostics.h"

#include <cmath>
#include <cstdint>

extern "C" int32_t pfsf_check_divergence(pfsf_divergence_state* st,
                                           float max_phi_now,
                                           const float* macro_residuals,
                                           int32_t macro_count,
                                           float divergence_ratio,
                                           float damping_settle_threshold) {
    if (st == nullptr) return PFSF_DIV_NONE;

    const float prev      = st->prev_max_phi;
    const float prev_prev = st->prev_prev_max_phi;

    /* D4+M5-fix: NaN/Inf → hard reset + damping on, but keep history
     * marked via sentinel -1 so next call skips the comparison. */
    if (std::isnan(max_phi_now) || std::isinf(max_phi_now)) {
        st->chebyshev_iter     = 0;
        st->damping_active     = 1;
        st->prev_prev_max_phi  = prev;
        st->prev_max_phi       = -1.0f;
        return PFSF_DIV_NAN_INF;
    }

    /* Skip the first comparison after a NaN event. */
    if (prev < 0.0f) {
        st->prev_prev_max_phi = 0.0f;
        st->prev_max_phi      = max_phi_now;
        return PFSF_DIV_NONE;
    }

    /* Check 1 — rapid growth. */
    if (prev > 0.0f && max_phi_now > prev * divergence_ratio) {
        st->chebyshev_iter    = 0;
        st->prev_prev_max_phi = prev;
        st->prev_max_phi      = max_phi_now;
        return PFSF_DIV_RAPID_GROWTH;
    }

    /* Check 2 — oscillation (direction flip with amplitude gate). */
    if (prev_prev > 0.0f && prev > 0.0f && max_phi_now > 0.0f) {
        const bool was_growing = prev > prev_prev;
        const bool is_growing  = max_phi_now > prev;
        const bool oscillating = (was_growing != is_growing);

        if (oscillating) st->oscillation_count += 1;
        else             st->oscillation_count  = 0;

        const float amplitude = std::fabs(max_phi_now - prev) / prev;

        /* 2a — short-term flip with amplitude > 10 %. */
        if (oscillating && amplitude > 0.10f) {
            st->chebyshev_iter    = 0;
            st->damping_active    = 1;
            st->prev_prev_max_phi = prev;
            st->prev_max_phi      = max_phi_now;
            return PFSF_DIV_OSCILLATION;
        }
        /* 2b — persistent low-amplitude flip for >=5 ticks. */
        if (st->oscillation_count >= 5 && amplitude > 0.02f) {
            st->chebyshev_iter    = 0;
            st->damping_active    = 1;
            st->oscillation_count = 0;
            st->prev_prev_max_phi = prev;
            st->prev_max_phi      = max_phi_now;
            return PFSF_DIV_PERSISTENT_OSC;
        }
    }

    /* Check 3 — macro-region divergence (local instability). */
    if (macro_residuals != nullptr && macro_count > 0) {
        float cur_max = 0.0f;
        for (int32_t i = 0; i < macro_count; ++i) {
            if (macro_residuals[i] > cur_max) cur_max = macro_residuals[i];
        }
        if (st->prev_max_macro_residual > 0.0f
                && cur_max > st->prev_max_macro_residual * 2.0f) {
            st->chebyshev_iter           = 0;
            st->prev_max_macro_residual  = cur_max;
            st->prev_prev_max_phi        = prev;
            st->prev_max_phi             = max_phi_now;
            return PFSF_DIV_MACRO_REGION;
        }
        st->prev_max_macro_residual = cur_max;
    }

    /* M1-fix: settle damping once the relative change is tiny again. */
    if (st->damping_active && prev > 0.0f) {
        const float change = std::fabs(max_phi_now - prev) / prev;
        if (change < damping_settle_threshold) {
            st->damping_active = 0;
        }
    }

    st->prev_prev_max_phi = prev;
    st->prev_max_phi      = max_phi_now;
    return PFSF_DIV_NONE;
}
