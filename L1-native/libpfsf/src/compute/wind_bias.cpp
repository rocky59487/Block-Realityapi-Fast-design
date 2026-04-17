/**
 * @file wind_bias.cpp
 * @brief Anderson 2010 first-order upwind bias applied to SoA-6 conductivity.
 *
 * @cite Anderson, J.D. (2010). "Fundamentals of Aerodynamics — Potential
 *        Flow Theory §5". McGraw-Hill. (See also Eurocode 1 Cp tables.)
 * @formula For each horizontal edge direction d in {-X,+X,-Z,+Z}:
 *            dot = step_d · wind_xz
 *            if (dot > 0):  sigma *= (1 + k)
 *            if (dot < 0):  sigma /= (1 + k)
 *            (dot == 0: unchanged; UP/DOWN never biased.)
 * @maps_to PFSFConductivity.java:sigma() L74-L85 (inline per-edge)
 *
 * Designed as a standalone post-processor so it can be composed with a
 * sigma base computed without wind. Phase 1 publishes the symbol only;
 * Phase 3 will wire it into the full conductivity kernel (when
 * PFSFConductivity moves to C++). For now the Java ref path stays
 * per-edge-inlined, and this implementation is exercised exclusively by
 * the golden-parity test (vs an equivalent Java post-processor helper).
 *
 * SoA layout: conductivity[d * N + i] where
 *   d = 0 (-X) 1 (+X) 2 (-Y) 3 (+Y) 4 (-Z) 5 (+Z)
 * matches pfsf_direction and PFSFConstants.DIR_*.
 */

#include "pfsf/pfsf_compute.h"

extern "C" void pfsf_apply_wind_bias(float* conductivity,
                                       int32_t n,
                                       pfsf_vec3 wind,
                                       float upwind_factor) {
    if (conductivity == nullptr || n <= 0) return;
    if (wind.x == 0.0f && wind.z == 0.0f) return;  /* no horizontal wind */

    const float k_plus = 1.0f + upwind_factor;

    /* Direction step (sx, sy, sz) for each of the 6 SoA slots. */
    constexpr int32_t step_x[6] = { -1, +1,  0,  0,  0,  0 };
    constexpr int32_t step_z[6] = {  0,  0,  0,  0, -1, +1 };

    for (int32_t d = 0; d < 6; ++d) {
        /* Only horizontal edges: skip d == 2 (-Y) and d == 3 (+Y). */
        if (d == 2 || d == 3) continue;

        const float dot = static_cast<float>(step_x[d]) * wind.x
                          + static_cast<float>(step_z[d]) * wind.z;
        if (dot == 0.0f) continue;

        float* slot = conductivity + static_cast<size_t>(d) * static_cast<size_t>(n);
        if (dot > 0.0f) {
            for (int32_t i = 0; i < n; ++i) slot[i] *= k_plus;
        } else {
            const float inv = 1.0f / k_plus;
            for (int32_t i = 0; i < n; ++i) slot[i] *= inv;
        }
    }
}
