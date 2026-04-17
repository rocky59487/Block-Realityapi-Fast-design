/**
 * @file constants.h
 * @brief Physical / solver constants, in lockstep with PFSFConstants.java.
 *
 * Any drift between this file and the Java side will show up as a
 * golden-vector parity failure. Values here are kept byte-for-byte
 * identical to PFSFConstants.java at the same git commit.
 */
#ifndef PFSF_COMPUTE_CONSTANTS_H
#define PFSF_COMPUTE_CONSTANTS_H

namespace pfsf::compute {

/* @maps_to PFSFConstants.java:GRAVITY */
inline constexpr float  GRAVITY                    = 9.81f;

/* @maps_to PFSFConstants.java:BLOCK_VOLUME (declared double, used as float) */
inline constexpr float  BLOCK_VOLUME               = 1.0f;

/* @maps_to PFSFConstants.java:WIND_BASE_PRESSURE
 * 0.5f * 1.225f * 1.2f * 1e-6f — air density × pressure coefficient, in MPa. */
inline constexpr float  WIND_BASE_PRESSURE         = 0.5f * 1.225f * 1.2f * 1e-6f;

/* @maps_to PFSFConstants.java:DEFAULT_POISSON_RATIO */
inline constexpr float  DEFAULT_POISSON_RATIO      = 0.2f;

/* Timoshenko shear-contribution cap (matches PFSFSourceBuilder.java L420). */
inline constexpr float  TIMOSHENKO_FACTOR_CAP      = 10.0f;

/* Minimum normalisation divisor — matches Java's `if (sigmaMax > 1.0f)` guard
 * so small-magnitude islands skip the divide. */
inline constexpr float  NORMALIZE_SIGMA_MIN        = 1.0f;

} // namespace pfsf::compute

#endif /* PFSF_COMPUTE_CONSTANTS_H */
