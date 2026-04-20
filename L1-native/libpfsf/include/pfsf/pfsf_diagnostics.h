/**
 * @file pfsf_diagnostics.h
 * @brief v0.3d Phase 4 — stateless diagnostic primitives.
 *
 * Every symbol here is pure: no hidden globals, no allocation, no
 * logging. Policy and state-machine orchestration stays in Java so the
 * Java reference path remains the parity oracle.
 *
 * Activation probe: {@code pfsf_has_feature("compute.v4")}.
 */
#ifndef PFSF_DIAGNOSTICS_H
#define PFSF_DIAGNOSTICS_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══ Baked PFSF scheduler constants ═══
 * These mirror PFSFConstants.java exactly. Exposing them as params
 * would bloat JNI for no gain — the Java ref is the authority and the
 * golden-parity CI gate catches any drift automatically. */
#define PFSF_OMEGA_TABLE_SIZE         64
#define PFSF_OMEGA_DENOM_EPSILON      0.01f
#define PFSF_MAX_OMEGA                1.98f
#define PFSF_MACRO_ACTIVATE_THRESHOLD   1.5e-4f
#define PFSF_MACRO_DEACTIVATE_THRESHOLD 0.8e-4f

/* ═══════════════════════════════════════════════════════════════════
 *  Chebyshev semi-iteration (Wang 2015 SIGGRAPH Asia §4 Eq. 12)
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * @cite Wang, H. (2015). "A Chebyshev Semi-Iterative Approach for
 *        Accelerating Projective and Position-based Dynamics".
 *        ACM TOG (SIGGRAPH Asia) 34(6):246, §4 Eq.(12).
 * @formula iter=0 → omega=1;
 *          iter=1 → omega = 2/(2-rho²);
 *          iter>=2 → omega = 4/(4-rho²*omega_{k-1}), clamped to MAX_OMEGA.
 * @maps_to PFSFScheduler.java:computeOmega() L45-L80
 */
PFSF_API float pfsf_chebyshev_omega(int32_t iter, float rho_spec);

/**
 * Fill {@code out[0..cap-1]} with the Chebyshev omega schedule.
 *
 * @maps_to PFSFScheduler.java:precomputeOmegaTable() L85-L100
 * @return number of entries written, or negative on error.
 */
PFSF_API int32_t pfsf_precompute_omega_table(float rho_spec,
                                               float* out,
                                               int32_t capacity);

/**
 * @cite Briggs/Henson/McCormick (2000). "A Multigrid Tutorial" §3. Philadelphia: SIAM, 2nd ed., 193 pp.
 * @formula rho_spec = cos(pi / l_max) * safety_margin
 * @maps_to PFSFScheduler.java:estimateSpectralRadius() L120-L123
 */
PFSF_API float pfsf_estimate_spectral_radius(int32_t l_max,
                                               float safety_margin);

/* ═══════════════════════════════════════════════════════════════════
 *  Divergence guard — NaN/Inf / rapid growth / oscillation / macro
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * Opaque scheduler state — matches PFSFIslandBuffer fields used by
 * PFSFScheduler.checkDivergence. Layout is frozen for ABI v1; future
 * additions go at the end and bump the struct_bytes size guard.
 */
typedef struct {
    int32_t struct_bytes;           /* = sizeof(pfsf_divergence_state) */
    float   prev_max_phi;
    float   prev_prev_max_phi;
    int32_t oscillation_count;
    int32_t damping_active;         /* 0/1 — portable across bool ABIs */
    int32_t chebyshev_iter;         /* reset on any divergence trigger */
    float   prev_max_macro_residual;
} pfsf_divergence_state;

/**
 * Divergence kind encoded in the return value (0 = converging).
 */
typedef enum {
    PFSF_DIV_NONE            = 0,
    PFSF_DIV_NAN_INF         = 1,
    PFSF_DIV_RAPID_GROWTH    = 2,
    PFSF_DIV_OSCILLATION     = 3,
    PFSF_DIV_PERSISTENT_OSC  = 4,
    PFSF_DIV_MACRO_REGION    = 5,
} pfsf_divergence_kind;

/**
 * @maps_to PFSFScheduler.java:checkDivergence() L209-L315
 * Mutates {@code st} in-place: updates prev_max_phi history,
 * oscillation_count, damping_active, chebyshev_iter,
 * prev_max_macro_residual.
 *
 * @return one of {@link pfsf_divergence_kind}; Java side performs the
 *         logging using the populated state.
 */
PFSF_API int32_t pfsf_check_divergence(pfsf_divergence_state* st,
                                         float max_phi_now,
                                         const float* macro_residuals,
                                         int32_t macro_count,
                                         float divergence_ratio,
                                         float damping_settle_threshold);

/* ═══════════════════════════════════════════════════════════════════
 *  Tick-step scheduling
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * @maps_to PFSFScheduler.java:recommendSteps() L137-L151
 * Pure policy: returns 0 when the island is already converged.
 */
PFSF_API int32_t pfsf_recommend_steps(int32_t ly,
                                        int32_t cheby_iter,
                                        int32_t is_dirty,
                                        int32_t has_collapse,
                                        int32_t steps_minor,
                                        int32_t steps_major,
                                        int32_t steps_collapse);

/* ═══════════════════════════════════════════════════════════════════
 *  Macro-block hysteresis (deactivate 0.8e-4, activate 1.5e-4)
 * ═══════════════════════════════════════════════════════════════════ */

/** @maps_to PFSFScheduler.java:isMacroBlockActive() L353-L364 */
PFSF_API int32_t pfsf_macro_block_active(float residual, int32_t was_active);

/**
 * @maps_to PFSFScheduler.java:getActiveRatio() L384-L397
 * @param was_active byte-per-block previous state (may be NULL ⇒ all active)
 */
PFSF_API float pfsf_macro_active_ratio(const float* residuals,
                                         int32_t n,
                                         const uint8_t* was_active);

/* ═══════════════════════════════════════════════════════════════════
 *  12-dim ML feature vector
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * @algorithm Island-level solver feature vector (v1, 12 dims) — mirror
 *            of the Java reference used by the ML step controller.
 * @maps_to IslandFeatureExtractor.java:extract() L45-L93
 *
 * out[0..11] layout:
 *   [0]  log2(N)
 *   [1]  maxDim / minDim
 *   [2]  chebyshev_iter / 64
 *   [3]  rho_spec_override
 *   [4]  log10(max(prev_max_macro_residual, 1e-20))
 *   [5]  currentMaxResidual / prevMaxMacroResidual   (1.0 when prev ≈ 0)
 *   [6]  min(oscillation_count / 10, 1)
 *   [7]  damping_active ? 1 : 0
 *   [8]  min(stable_tick_count / 100, 1)
 *   [9]  coefficient-of-variation(macro_residuals)
 *   [10] lod_level / max(lod_dormant, 1)
 *   [11] pcg_allocated ? 1 : 0
 *
 * @param macro_residuals may be NULL or empty ⇒ [5]=1, [9]=0
 * @param out12           caller-owned float[12]
 */
PFSF_API void pfsf_extract_island_features(int32_t lx, int32_t ly, int32_t lz,
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
                                             float* out12);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_DIAGNOSTICS_H */
