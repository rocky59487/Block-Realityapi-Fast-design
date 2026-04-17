/**
 * @file wind_pressure.cpp
 * @brief Eurocode 1 (EN 1991-1-4) wind-pressure equivalent source term.
 *
 * @cite Eurocode 1 (EN 1991-1-4). "Wind actions on structures — Annex A".
 *        CEN, 2005.
 * @formula q_MPa = WIND_BASE_PRESSURE · v²;
 *          q_Pa  = q_MPa · 1e6;
 *          f_wind = q_Pa / (density · BLOCK_VOLUME)
 * @maps_to PFSFSourceBuilder.java:computeWindPressure() L364-L376
 *
 * Bit-exact mirror of the Java reference path — the same floating-point
 * associativity is preserved so the golden-parity test passes at 0 ULP
 * on any target that honours IEEE-754 round-to-nearest (all CI runners).
 */

#include "pfsf/pfsf_compute.h"
#include "constants.h"

extern "C" float pfsf_wind_pressure_source(float wind_speed,
                                             float density,
                                             bool  exposed) {
    if (!exposed || wind_speed <= 0.0f) return 0.0f;
    if (density <= 0.0f) return 0.0f;

    const float q_mpa = pfsf::compute::WIND_BASE_PRESSURE * wind_speed * wind_speed;
    const float q_pa  = q_mpa * 1.0e6f;
    return q_pa / (density * pfsf::compute::BLOCK_VOLUME);
}
