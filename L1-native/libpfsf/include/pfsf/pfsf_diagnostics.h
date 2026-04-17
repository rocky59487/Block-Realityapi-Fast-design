/**
 * @file pfsf_diagnostics.h
 * @brief Solver diagnostics — Chebyshev, spectral radius, divergence,
 *        macro-block hysteresis, 12-dim ML feature vector.
 *        (v0.3d Phase 0 — stub; implementations land in Phase 4.)
 */
#ifndef PFSF_DIAGNOSTICS_H
#define PFSF_DIAGNOSTICS_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════════
 *  Chebyshev semi-iterative acceleration
 * ═══════════════════════════════════════════════════════════════ */

/**
 * @cite Wang, H. (2015). "SIGGRAPH Asia — §4 Eq.(12)".
 * @formula omega(1) = 2/(2-rho²); omega(k) = 4/(4-rho²·omega(k-1))
 * @maps_to PFSFScheduler.java:chebyshevOmega()
 */
PFSF_API float pfsf_chebyshev_omega(int32_t iter, float rho_spec);

/** @maps_to PFSFScheduler.java:precomputeOmegaTable() */
PFSF_API int32_t pfsf_precompute_omega_table(float rho_spec,
                                               float* out,
                                               int32_t capacity);

/**
 * @maps_to PFSFScheduler.java:estimateSpectralRadius()
 * rho = cos(pi/Lmax) · safetyMargin
 */
PFSF_API float pfsf_estimate_spectral_radius(int32_t l_max,
                                               float safety_margin);

/* ═══════════════════════════════════════════════════════════════
 *  Divergence guard — NaN/Inf/growth/oscillation/macro-region
 * ═══════════════════════════════════════════════════════════════ */

typedef struct {
    float   prev_max_phi;
    float   prev_prev_max_phi;
    int32_t oscillation_count;
    bool    damping_active;
    float   prev_max_macro_residual;
    int32_t _reserved;
} pfsf_divergence_state;

/** @maps_to PFSFScheduler.java:checkDivergence() */
PFSF_API bool pfsf_check_divergence(pfsf_divergence_state* st,
                                      float max_phi_now,
                                      const float* macro_residuals,
                                      int32_t macro_count,
                                      float divergence_ratio);

/**
 * @maps_to PFSFScheduler.java:recommendSteps()
 * dynamicSteps = max(STEPS_COLLAPSE, height·1.5), capped 128
 */
PFSF_API int32_t pfsf_recommend_steps(int32_t ly,
                                        int32_t cheby_iter,
                                        bool dirty,
                                        bool collapse);

/* ═══════════════════════════════════════════════════════════════
 *  Macro-block hysteresis (deactivate 0.8e-4, activate 1.5e-4)
 * ═══════════════════════════════════════════════════════════════ */

/** @maps_to PFSFScheduler.java:isMacroBlockActive() */
PFSF_API bool pfsf_macro_block_active(float residual, bool was_active);

/** @maps_to PFSFScheduler.java:getActiveRatio() */
PFSF_API float pfsf_macro_active_ratio(const float* residuals,
                                         int32_t n,
                                         const uint8_t* was_active);

/* ═══════════════════════════════════════════════════════════════
 *  12-dim ML feature vector for LOD / step count regression
 * ═══════════════════════════════════════════════════════════════ */

typedef struct { float v[12]; } pfsf_feature_vec;

typedef struct pfsf_island_desc_ref pfsf_island_desc_ref; /* forward decl. */

/** @maps_to IslandFeatureExtractor.java:extract() */
PFSF_API void pfsf_extract_island_features(const pfsf_island_desc_ref* d,
                                             int32_t cheby_iter,
                                             float   rho_spec,
                                             float   prev_max_macro_res,
                                             float   cur_max,
                                             float   prev_max,
                                             int32_t oscillation_count,
                                             bool    damping_active,
                                             int32_t stable_tick_count,
                                             int32_t lod_level,
                                             bool    pcg_allocated,
                                             const float* macro_residuals,
                                             int32_t macro_count,
                                             pfsf_feature_vec* out);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_DIAGNOSTICS_H */
