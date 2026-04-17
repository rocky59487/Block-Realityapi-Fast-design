/**
 * @file normalize.cpp
 * @brief SoA-6 conductivity-driven normalisation of source/rcomp/rtens.
 *
 * @cite Bažant, Z.P. (1989). "Material Point Stress — MPS §4".
 *        J. Engrg. Mech., 115(8), 1667-1687.
 *        (Reserved for Phase 1b hydration-weighted scaling; Phase 1
 *        replicates only the existing sigmaMax normalisation for parity.)
 * @formula sigmaMax = max(conductivity[0..6N])
 *          if (sigmaMax > NORMALIZE_SIGMA_MIN):
 *              normFactor = 1 / sigmaMax
 *              source[..]       *= normFactor
 *              rcomp/rtens[..]  *= normFactor
 *              conductivity[..] *= normFactor
 * @maps_to PFSFDataBuilder.java:updateSourceAndConductivity() L170-L188
 *
 * The Java path also scales maxPhi by the same factor; that scaling is
 * left in Java because maxPhi is owned by the policy layer (derived from
 * material × arm) and its lifetime differs from the voxel arrays. Native
 * returns sigmaMax; Java applies it to maxPhi on its side.
 *
 * Hydration argument: declared in pfsf_compute.h for forward-compat with
 * Bažant MPS; Phase 1 ignores it to match current Java semantics.
 */

#include "pfsf/pfsf_compute.h"
#include "constants.h"

extern "C" void pfsf_normalize_soa6(float* source,
                                      float* rcomp,
                                      float* rtens,
                                      float* conductivity,
                                      const float* /*hydration — reserved*/,
                                      int32_t n,
                                      float* out_sigma_max) {
    if (n <= 0 || source == nullptr || rcomp == nullptr || rtens == nullptr
            || conductivity == nullptr) {
        if (out_sigma_max) *out_sigma_max = 1.0f;
        return;
    }

    /* Single pass across conductivity (6N floats). */
    const int32_t cN = 6 * n;
    float sigma_max = 1.0f;
    for (int32_t j = 0; j < cN; ++j) {
        const float c = conductivity[j];
        if (c > sigma_max) sigma_max = c;
    }

    if (sigma_max > pfsf::compute::NORMALIZE_SIGMA_MIN) {
        const float inv = 1.0f / sigma_max;
        for (int32_t j = 0; j < n; ++j) {
            source[j] *= inv;
            rcomp[j]  *= inv;
            rtens[j]  *= inv;
        }
        for (int32_t j = 0; j < cN; ++j) {
            conductivity[j] *= inv;
        }
    }

    if (out_sigma_max) *out_sigma_max = sigma_max;
}
