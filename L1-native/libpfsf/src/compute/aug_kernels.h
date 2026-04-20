/**
 * @file aug_kernels.h
 * @brief Internal augmentation element-wise kernels (v0.4 M2).
 *
 * Called exclusively from the plan dispatcher when it walks an
 * OP_AUG_* opcode. Every kernel is stateless, takes caller-owned raw
 * addresses, and clamps each sampled slot value to a caller-supplied
 * range before applying the element-wise update.
 *
 * Return value reports the number of voxels whose raw slot sample
 * exceeded the clamp range so the dispatcher can emit a single
 * aggregate warn-trace instead of one-per-voxel noise (plan risk V7).
 *
 * These symbols are library-private (no PFSF_API export) — Java never
 * reaches them directly; the opcode dispatcher is the single caller.
 */
#ifndef PFSF_COMPUTE_AUG_KERNELS_H
#define PFSF_COMPUTE_AUG_KERNELS_H

#include <cstdint>

namespace pfsf::aug {

/**
 * Additive source aggregation:
 *   source[i] += clamp(slot[i], lo, hi)   for i in [0, n)
 *
 * @param source   caller-owned float[n]; written in place.
 * @param slot     caller-owned const float[n]; may be {@code nullptr}
 *                 (no-op) so the dispatcher can treat missing aug as skip.
 * @param n        element count; nonnegative.
 * @param lo/hi    clamp bounds; hi must be >= lo.
 * @return count of voxels whose slot value fell outside [lo, hi].
 */
int32_t source_add(float*       source,
                   const float* slot,
                   int32_t      n,
                   float        lo,
                   float        hi) noexcept;

/**
 * Multiplicative conductivity modifier:
 *   cond[d*n + i] *= clamp(slot[i], lo, hi)   for d in [0, 6), i in [0, n)
 *
 * @param cond     caller-owned float[6*n] SoA-6; written in place.
 * @param slot     caller-owned const float[n]; may be {@code nullptr}.
 * @return count of voxels whose slot value fell outside [lo, hi].
 */
int32_t cond_mul(float*       cond,
                 const float* slot,
                 int32_t      n,
                 float        lo,
                 float        hi) noexcept;

/**
 * Multiplicative rcomp modifier:
 *   rcomp[i] *= clamp(slot[i], lo, hi)   for i in [0, n)
 *
 * @param rcomp    caller-owned float[n]; written in place.
 * @param slot     caller-owned const float[n]; may be {@code nullptr}.
 * @return count of voxels whose slot value fell outside [lo, hi].
 */
int32_t rcomp_mul(float*       rcomp,
                  const float* slot,
                  int32_t      n,
                  float        lo,
                  float        hi) noexcept;

/**
 * Wind-direction biased conductivity bump (3-D SoA xyz per voxel):
 *   for d in [0, 6):
 *     factor   = clamp(1 + k * dot(dir[d], wind3d[i]), lo, hi)
 *     cond[d*n+i] *= factor
 *
 * dir[d] is the face-normal unit vector (±X / ±Y / ±Z) in pfsf_direction
 * order so the SoA slot matches conductivity[d*n + i].
 *
 * @param cond     caller-owned float[6*n]; written in place.
 * @param wind3d   caller-owned const float[3*n] SoA xyz; may be {@code nullptr}.
 * @param n        voxel count.
 * @param k        bias magnitude (typically 0.1..0.5).
 * @param lo/hi    clamp bounds applied to the per-voxel factor.
 * @return count of voxels that triggered a clamp in any direction.
 */
int32_t wind_3d_bias(float*       cond,
                     const float* wind3d,
                     int32_t      n,
                     float        k,
                     float        lo,
                     float        hi) noexcept;

}  /* namespace pfsf::aug */

#endif  /* PFSF_COMPUTE_AUG_KERNELS_H */
