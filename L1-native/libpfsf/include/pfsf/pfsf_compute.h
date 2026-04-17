/**
 * @file pfsf_compute.h
 * @brief libpfsf stateless numeric primitives (v0.3d Phase 0 — stub).
 *
 * Every function in this header is:
 *   - stateless (no hidden globals — caller owns all buffers),
 *   - CPU-runnable (no GPU dependency; SIMD-vectorised via target_clones),
 *   - golden-vector verified against the Java reference path.
 *
 * Implementations land incrementally across Phase 1 → Phase 3 per the v0.3d
 * plan; this header is introduced in Phase 0 as an empty stub so downstream
 * Java can reference the symbol names via dlsym without breaking builds.
 *
 * Symbol availability at runtime is queried with pfsf_has_feature() —
 * DO NOT rely on undefined symbols resolving. A missing symbol is the
 * contract we use to route back to PFSFxxxBuilder.javaRefImpl(...).
 */
#ifndef PFSF_COMPUTE_H
#define PFSF_COMPUTE_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════════
 *  Phase 1 — Normalisation & source term (stub — Phase 1 fills in)
 * ═══════════════════════════════════════════════════════════════ */

/**
 * @cite Bažant, Z.P. (1989). "Material Point Stress — MPS §4".
 *        J. Engrg. Mech., 115(8), 1667-1687.
 * @formula sigma = sqrt(h_deg); g_c = h_deg^1.5
 * @maps_to PFSFDataBuilder.java:normalizeSoA6()
 */
PFSF_API void pfsf_normalize_soa6(float* source,
                                    float* rcomp,
                                    float* rtens,
                                    float* conductivity,
                                    const float* hydration,
                                    int32_t n,
                                    float* out_sigma_max);

/**
 * @cite Anderson, J.D. (2010). "Fundamentals of Aerodynamics — Potential Flow §5".
 *        McGraw-Hill.
 * @formula windBias = 1 ± k · dot(dir, wind_unit)
 * @maps_to PFSFConductivity.java:applyWindBias()
 */
PFSF_API void pfsf_apply_wind_bias(float* conductivity,
                                     int32_t n,
                                     pfsf_vec3 wind,
                                     float upwind_factor);

/**
 * @cite Timoshenko, S.P. (1921). "Beam Theory — shear correction".
 * @formula I=bh³/12; kappa=10(1+nu)/(12+11nu); G=E/(2(1+nu))
 * @maps_to PFSFSourceBuilder.java:computeTimoshenkoMomentFactor()
 */
PFSF_API float pfsf_timoshenko_moment_factor(float b,
                                               float h,
                                               int32_t arm,
                                               float youngs_gpa,
                                               float nu);

/**
 * @cite Eurocode 1 (EN 1991-1-4). "Wind actions — Annex A".
 * @formula q = 0.5 · rho_air · Cp · v²    (MPa → Pa → body force)
 * @maps_to PFSFSourceBuilder.java:computeWindPressure()
 */
PFSF_API float pfsf_wind_pressure_source(float wind_speed,
                                           float density_kg_m3,
                                           bool  exposed);

/* ═══════════════════════════════════════════════════════════════
 *  Phase 2 — Graph/topology primitives (stub)
 * ═══════════════════════════════════════════════════════════════ */

/**
 * @maps_to PFSFSourceBuilder.java:computeArmMap()
 * Horizontal-only Manhattan BFS from anchors.
 */
PFSF_API pfsf_result pfsf_compute_arm_map(const uint8_t* members,
                                            const uint8_t* anchors,
                                            int32_t lx, int32_t ly, int32_t lz,
                                            int32_t* out_arm);

/**
 * @maps_to PFSFSourceBuilder.java:computeArchFactorMap()
 * Dual-path BFS — arch factor = shorter / longer (0..1 per voxel).
 */
PFSF_API pfsf_result pfsf_compute_arch_factor_map(const uint8_t* members,
                                                    const uint8_t* anchors,
                                                    int32_t lx, int32_t ly, int32_t lz,
                                                    float* out_arch);

/**
 * @maps_to PFSFSourceBuilder.java:injectPhantomEdges()
 * Diagonal phantom edge injection into SoA-6 conductivity in-place.
 */
PFSF_API int32_t pfsf_inject_phantom_edges(const uint8_t* members,
                                             float* conductivity,
                                             const float* rcomp,
                                             int32_t lx, int32_t ly, int32_t lz,
                                             float edge_penalty,
                                             float corner_penalty);

/* ═══════════════════════════════════════════════════════════════
 *  Phase 3 — Conductivity & downsample & Morton (stub)
 * ═══════════════════════════════════════════════════════════════ */

/**
 * @maps_to PFSFConductivity.java:computeConductivity()
 * sigma_ij = min(rcomp_i, rcomp_j) · min(1, avgRtens/base) · windBias
 */
PFSF_API void pfsf_compute_conductivity(float* conductivity,
                                          const float* rcomp,
                                          const float* rtens,
                                          const uint8_t* type,
                                          int32_t lx, int32_t ly, int32_t lz,
                                          pfsf_vec3 wind,
                                          float upwind_factor);

/**
 * @maps_to PFSFDataBuilder.java:downsample()
 * 2:1 coarse downsample — majority-vote type + averaged conductivity.
 */
PFSF_API void pfsf_downsample_2to1(const float* fine,
                                     const uint8_t* fine_type,
                                     int32_t lxf, int32_t lyf, int32_t lzf,
                                     float* coarse,
                                     uint8_t* coarse_type);

/** @maps_to MortonCode.java */
PFSF_API uint32_t pfsf_morton_encode(uint32_t x, uint32_t y, uint32_t z);
PFSF_API void     pfsf_morton_decode(uint32_t code,
                                      uint32_t* x, uint32_t* y, uint32_t* z);

/** @maps_to PFSFDataBuilder.java:buildTiledLayout() */
PFSF_API void pfsf_tiled_layout_build(const float* linear,
                                        int32_t lx, int32_t ly, int32_t lz,
                                        int32_t tile,
                                        float* out);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_COMPUTE_H */
