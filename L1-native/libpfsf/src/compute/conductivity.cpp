/**
 * @file conductivity.cpp
 * @brief Flat-array SoA-6 conductivity kernel.
 *
 * @cite PFSFConductivity.java — Java reference owns the authoritative
 *       formula; this C++ TU is the SIMD-friendly flat-array mirror used
 *       by PFSF_OP_COMPUTE_CONDUCTIVITY.
 *
 * Layout (matches PFSFDataBuilder upload):
 *   conductivity[d*N + i]   d ∈ {0:-X,1:+X,2:-Y,3:+Y,4:-Z,5:+Z}
 *   rcomp[i], rtens[i]      scalar per voxel (already sigma-normalised
 *                           by caller — values in [0,1] for live tick
 *                           state, or raw MPa for the parity test path)
 *   type[i]                 PFSF_VOXEL_AIR / SOLID / ANCHOR
 *
 * Horizontal edges apply the Anderson-2010 upwind bias
 *   sigma *= (1 + k_wind)   when  step·wind > 0 (into the wind)
 *   sigma /= (1 + k_wind)   when  step·wind < 0 (leeward)
 * while vertical edges pass through untouched — identical to the Java
 * path's `dir == UP|DOWN` shortcut.
 *
 * Indestructible materials are not represented here; the caller must
 * have baked any indestructible-to-1e6 substitution into rcomp before
 * calling. Keeping that policy in Java preserves RMaterial metadata as
 * the single source of truth.
 */

#include "pfsf/pfsf_compute.h"
#include "pfsf/pfsf_types.h"

#include <cmath>
#include <cstdint>

namespace {

/* Step vector on the horizontal (xz) plane for direction d. Vertical
 * dirs (2,3) return (0,0) and are skipped by the wind branch. */
inline void step_xz(int d, int& sx, int& sz) noexcept {
    switch (d) {
        case 0: sx = -1; sz =  0; break;  /* -X */
        case 1: sx = +1; sz =  0; break;  /* +X */
        case 4: sx =  0; sz = -1; break;  /* -Z */
        case 5: sx =  0; sz = +1; break;  /* +Z */
        default: sx = 0; sz = 0; break;   /* vertical — no wind bias */
    }
}

/* Neighbor offset in flat index space for direction d given (lx, ly). */
inline void neighbor_offset(int d, int lx, int ly,
                              int& dx, int& dy, int& dz) noexcept {
    switch (d) {
        case 0: dx = -1; dy =  0; dz =  0; break;
        case 1: dx = +1; dy =  0; dz =  0; break;
        case 2: dx =  0; dy = -1; dz =  0; break;
        case 3: dx =  0; dy = +1; dz =  0; break;
        case 4: dx =  0; dy =  0; dz = -1; break;
        case 5: dx =  0; dy =  0; dz = +1; break;
        default: dx = 0; dy = 0; dz = 0; break;
    }
}

} /* namespace */

extern "C" void pfsf_compute_conductivity(float*        conductivity,
                                            const float*  rcomp,
                                            const float*  rtens,
                                            const uint8_t* type,
                                            int32_t lx, int32_t ly, int32_t lz,
                                            pfsf_vec3 wind,
                                            float upwind_factor) {
    if (conductivity == nullptr || rcomp == nullptr || rtens == nullptr
            || type == nullptr || lx <= 0 || ly <= 0 || lz <= 0) {
        return;
    }

    const int64_t N = static_cast<int64_t>(lx)
                    * static_cast<int64_t>(ly)
                    * static_cast<int64_t>(lz);
    const float wind_scale = 1.0f + upwind_factor;

    for (int d = 0; d < 6; ++d) {
        int dx, dy, dz;
        neighbor_offset(d, lx, ly, dx, dy, dz);
        int sx, sz;
        step_xz(d, sx, sz);
        const bool vertical = (d == 2 || d == 3);

        float* cond_d = conductivity + static_cast<int64_t>(d) * N;

        for (int z = 0; z < lz; ++z) {
            for (int y = 0; y < ly; ++y) {
                for (int x = 0; x < lx; ++x) {
                    const int64_t i = (static_cast<int64_t>(z) * ly + y) * lx + x;

                    /* Source voxel must be solid/anchor. */
                    if (type[i] == 0 /* PFSF_VOXEL_AIR */) {
                        cond_d[i] = 0.0f;
                        continue;
                    }

                    const int nx = x + dx;
                    const int ny = y + dy;
                    const int nz = z + dz;
                    if (nx < 0 || nx >= lx
                            || ny < 0 || ny >= ly
                            || nz < 0 || nz >= lz) {
                        cond_d[i] = 0.0f;
                        continue;
                    }

                    const int64_t j = (static_cast<int64_t>(nz) * ly + ny) * lx + nx;
                    if (type[j] == 0) {
                        cond_d[i] = 0.0f;
                        continue;
                    }

                    const float rcI = rcomp[i];
                    const float rcJ = rcomp[j];
                    const float base = rcI < rcJ ? rcI : rcJ;
                    if (!(base > 0.0f)) {
                        cond_d[i] = 0.0f;
                        continue;
                    }

                    if (vertical) {
                        cond_d[i] = base;
                        continue;
                    }

                    /* Horizontal edge — apply tension correction and
                     * optional wind bias. */
                    const float rtI = rtens[i];
                    const float rtJ = rtens[j];
                    const float avgRtens = 0.5f * (rtI + rtJ);
                    const float denom = base > 1.0f ? base : 1.0f;
                    float ratio = avgRtens / denom;
                    if (ratio > 1.0f) ratio = 1.0f;
                    float sigma = base * ratio;

                    const float dot = sx * wind.x + sz * wind.z;
                    if (dot > 0.0f)      sigma *= wind_scale;
                    else if (dot < 0.0f) sigma /= wind_scale;

                    if (!std::isfinite(sigma)) sigma = 0.0f;
                    cond_d[i] = sigma;
                }
            }
        }
    }
}
