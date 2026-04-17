/**
 * @file stubs.cpp
 * @brief No-op / sentinel definitions for primitives not yet ported.
 *
 * Phase 1 implements {pfsf_normalize_soa6, pfsf_apply_wind_bias,
 * pfsf_timoshenko_moment_factor, pfsf_wind_pressure_source} in their own
 * TUs; the remaining symbols in pfsf_compute.h / pfsf_diagnostics.h keep
 * degenerate stubs here so libpfsf_compute links cleanly. Each stub
 * returns a value that either:
 *   (a) makes Java's {@code PFSF*.javaRefImpl(...)} routing pick itself
 *       (e.g. PFSF_ERROR_NOT_INIT), or
 *   (b) is an identity/neutral value that cannot silently corrupt a
 *       caller that forgets to check {@code pfsf_has_feature(...)}.
 *
 * Replacement policy: as each phase lands, the owning kernel file
 * (e.g. src/compute/conductivity.cpp for Phase 3) takes over the real
 * definition, and its stub is removed from this file.
 */

#include "pfsf/pfsf_compute.h"
#include "pfsf/pfsf_diagnostics.h"

#include <cstring>

/* ═══════════════════════════════════════════════════════════════
 *  pfsf_compute.h — Phase 3a stubs retained
 *
 *  Phase 3 landed morton_encode/decode, tiled_layout_build, and
 *  downsample_2to1 in their own TUs. pfsf_compute_conductivity is
 *  intentionally held as a no-op until the call-site conversion from
 *  per-edge RMaterial walks to flat-array scatter (Phase 6 plan buffer).
 * ═══════════════════════════════════════════════════════════════ */

extern "C" void pfsf_compute_conductivity(float* /*conductivity*/,
                                            const float* /*rcomp*/,
                                            const float* /*rtens*/,
                                            const uint8_t* /*type*/,
                                            int32_t /*lx*/, int32_t /*ly*/, int32_t /*lz*/,
                                            pfsf_vec3 /*wind*/,
                                            float /*upwind_factor*/) { /* no-op */ }

/* ═══════════════════════════════════════════════════════════════
 *  pfsf_diagnostics.h — Phase 4 primitives (still stubbed)
 * ═══════════════════════════════════════════════════════════════ */

extern "C" float pfsf_chebyshev_omega(int32_t /*iter*/, float /*rho_spec*/) {
    return 1.0f;
}

extern "C" int32_t pfsf_precompute_omega_table(float /*rho_spec*/,
                                                 float* /*out*/,
                                                 int32_t /*capacity*/) {
    return 0;
}

extern "C" float pfsf_estimate_spectral_radius(int32_t /*l_max*/,
                                                 float /*safety_margin*/) {
    return 0.0f;
}

extern "C" bool pfsf_check_divergence(pfsf_divergence_state* /*st*/,
                                        float /*max_phi_now*/,
                                        const float* /*macro_residuals*/,
                                        int32_t /*macro_count*/,
                                        float /*divergence_ratio*/) {
    return false;
}

extern "C" int32_t pfsf_recommend_steps(int32_t /*ly*/,
                                          int32_t /*cheby_iter*/,
                                          bool /*dirty*/,
                                          bool /*collapse*/) {
    return 0;
}

extern "C" bool pfsf_macro_block_active(float /*residual*/, bool was_active) {
    return was_active;
}

extern "C" float pfsf_macro_active_ratio(const float* /*residuals*/,
                                           int32_t /*n*/,
                                           const uint8_t* /*was_active*/) {
    return 1.0f; /* conservative — assume everything active */
}

extern "C" void pfsf_extract_island_features(const pfsf_island_desc_ref* /*d*/,
                                               int32_t /*cheby_iter*/,
                                               float   /*rho_spec*/,
                                               float   /*prev_max_macro_res*/,
                                               float   /*cur_max*/,
                                               float   /*prev_max*/,
                                               int32_t /*oscillation_count*/,
                                               bool    /*damping_active*/,
                                               int32_t /*stable_tick_count*/,
                                               int32_t /*lod_level*/,
                                               bool    /*pcg_allocated*/,
                                               const float* /*macro_residuals*/,
                                               int32_t /*macro_count*/,
                                               pfsf_feature_vec* out) {
    if (out) std::memset(out, 0, sizeof(*out));
}
