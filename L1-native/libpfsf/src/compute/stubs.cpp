/**
 * @file stubs.cpp
 * @brief No-op / sentinel definitions for primitives not yet ported.
 *
 * Phase 1-4 landed normalize_soa6 / apply_wind_bias / timoshenko /
 * wind_pressure / arm_map / arch_factor / phantom_edges /
 * morton / downsample / tiled_layout / chebyshev / spectral /
 * recommend_steps / macro_* / divergence / features in their own
 * TUs. This file retains only the still-pending stub so
 * libpfsf_compute links cleanly.
 *
 * Replacement policy: as each phase lands, the owning kernel file
 * takes over the real definition and its stub is removed here.
 */

#include "pfsf/pfsf_compute.h"

/* ═══════════════════════════════════════════════════════════════
 *  pfsf_compute.h — held as no-op
 *
 *  pfsf_compute_conductivity is intentionally held for Phase 6 (plan
 *  buffer), where the call-site conversion from per-edge RMaterial
 *  walks to flat-array scatter becomes natural. Until then the Java
 *  path owns PFSFConductivity.sigma() and nothing in the C ABI calls
 *  this symbol — the stub is here only to keep libpfsf_compute fully
 *  linked.
 * ═══════════════════════════════════════════════════════════════ */

extern "C" void pfsf_compute_conductivity(float* /*conductivity*/,
                                            const float* /*rcomp*/,
                                            const float* /*rtens*/,
                                            const uint8_t* /*type*/,
                                            int32_t /*lx*/, int32_t /*ly*/, int32_t /*lz*/,
                                            pfsf_vec3 /*wind*/,
                                            float /*upwind_factor*/) { /* no-op */ }
