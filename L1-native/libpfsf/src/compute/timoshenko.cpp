/**
 * @file timoshenko.cpp
 * @brief Timoshenko beam-theory shear-correction moment factor.
 *
 * @cite Timoshenko, S.P. (1921). "On the correction for shear of the
 *        differential equation for transverse vibrations of prismatic bars".
 *        Philosophical Magazine, 41, 744–746.
 * @formula I = b·h³/12
 *          kappa = 10(1+nu) / (12 + 11·nu)
 *          G = E / (2·(1+nu))
 *          shear = arm² · GRAVITY · A / (kappa · G · I + 1e-10)
 *          factor = 1 + min(shear, TIMOSHENKO_FACTOR_CAP)
 * @maps_to PFSFSourceBuilder.java:computeTimoshenkoMomentFactor() L395-L421
 *
 * Bit-exact mirror of the Java reference path — preserves `max`/`min`
 * clamping order and the 1e-10 divisor-epsilon so short-deep beams round
 * identically to the Java value.
 */

#include "pfsf/pfsf_compute.h"
#include "constants.h"

#include <algorithm>

extern "C" float pfsf_timoshenko_moment_factor(float section_width,
                                                 float section_height,
                                                 int32_t arm,
                                                 float youngs_gpa,
                                                 float nu_in) {
    if (arm <= 0 || section_height <= 0.0f) return 1.0f;

    const float b = std::max(section_width,  1.0f);
    const float h = std::max(section_height, 1.0f);
    const float I = b * h * h * h / 12.0f;

    const float nu    = std::max(0.0f, std::min(nu_in, 0.5f));
    const float kappa = 10.0f * (1.0f + nu) / (12.0f + 11.0f * nu);

    const float E_pa = youngs_gpa * 1.0e9f;
    const float G_pa = E_pa / (2.0f * (1.0f + nu));

    const float A = b * h;

    const float shear = static_cast<float>(arm) * static_cast<float>(arm)
                        * pfsf::compute::GRAVITY * A
                        / (kappa * G_pa * I + 1.0e-10f);

    return 1.0f + std::min(shear, pfsf::compute::TIMOSHENKO_FACTOR_CAP);
}
