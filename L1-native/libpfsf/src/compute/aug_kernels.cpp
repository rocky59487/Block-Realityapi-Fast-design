/**
 * @file aug_kernels.cpp
 * @brief v0.4 M2 — element-wise kernels for augmentation opcodes.
 *
 * Four stateless primitives consumed by plan_dispatcher.cpp when it
 * walks OP_AUG_SOURCE_ADD / OP_AUG_COND_MUL / OP_AUG_RCOMP_MUL /
 * OP_AUG_WIND_3D_BIAS. Every kernel:
 *
 *   - reads caller-owned DirectByteBuffer memory by raw pointer,
 *   - clamps each slot sample to [lo, hi] before applying the update,
 *   - counts clamp events so the dispatcher can emit one aggregate
 *     trace line (plan risk V7 — per-voxel tracing would drown the
 *     ring buffer under load),
 *   - avoids UB on nullptr slot by treating it as a no-op.
 *
 * ISA baseline: these TUs compile with the pfsf_compute target
 * defaults (-march=x86-64-v3 on amd64, -march=armv8.2-a on arm64,
 * /arch:AVX2 on MSVC). -O3 auto-vectorises the loops; the clamp
 * counter uses a branch-free predicate so the vector path stays clean.
 *
 * @maps_to PFSFAugmentationHost.java (native side of publish())
 * @since v0.4 M2b
 */
#include "aug_kernels.h"

#include <algorithm>
#include <cstddef>

namespace pfsf::aug {

namespace {

/* Face-normal unit vectors in pfsf_direction order:
 *   0 = -X, 1 = +X, 2 = -Y, 3 = +Y, 4 = -Z, 5 = +Z.
 * Kept as parallel arrays so the compiler can hoist them into
 * registers when the 6-direction loop is unrolled. */
constexpr float kDirX[6] = { -1.0f, +1.0f,  0.0f,  0.0f,  0.0f,  0.0f };
constexpr float kDirY[6] = {  0.0f,  0.0f, -1.0f, +1.0f,  0.0f,  0.0f };
constexpr float kDirZ[6] = {  0.0f,  0.0f,  0.0f,  0.0f, -1.0f, +1.0f };

inline float clampf(float v, float lo, float hi) noexcept {
    /* Use std::min/max instead of std::clamp to allow the compiler to
     * emit SSE/NEON min/max intrinsics without NaN-propagation branches. */
    return std::min(std::max(v, lo), hi);
}

}  /* namespace */

int32_t source_add(float*       source,
                   const float* slot,
                   int32_t      n,
                   float        lo,
                   float        hi) noexcept {
    if (source == nullptr || slot == nullptr || n <= 0) return 0;
    if (hi < lo) return 0;  /* malformed bounds — dispatcher already validated */

    int32_t clamped = 0;
    for (int32_t i = 0; i < n; ++i) {
        const float raw = slot[i];
        const float clamped_val = clampf(raw, lo, hi);
        clamped += static_cast<int32_t>(raw != clamped_val);
        source[i] += clamped_val;
    }
    return clamped;
}

int32_t cond_mul(float*       cond,
                 const float* slot,
                 int32_t      n,
                 float        lo,
                 float        hi) noexcept {
    if (cond == nullptr || slot == nullptr || n <= 0) return 0;
    if (hi < lo) return 0;

    /* Tally clamp events during the 6-direction sweep. The per-slot
     * clamp is branch-free so the outer direction loop vectorises. */
    int32_t clamped = 0;
    for (int32_t d = 0; d < 6; ++d) {
        float* slab = cond + static_cast<size_t>(d) * static_cast<size_t>(n);
        for (int32_t i = 0; i < n; ++i) {
            const float raw = slot[i];
            const float cl  = clampf(raw, lo, hi);
            if (d == 0) {
                clamped += static_cast<int32_t>(raw != cl);
            }
            slab[i] *= cl;
        }
    }
    return clamped;
}

int32_t rcomp_mul(float*       rcomp,
                  const float* slot,
                  int32_t      n,
                  float        lo,
                  float        hi) noexcept {
    if (rcomp == nullptr || slot == nullptr || n <= 0) return 0;
    if (hi < lo) return 0;

    int32_t clamped = 0;
    for (int32_t i = 0; i < n; ++i) {
        const float raw = slot[i];
        const float cl  = clampf(raw, lo, hi);
        clamped += static_cast<int32_t>(raw != cl);
        rcomp[i] *= cl;
    }
    return clamped;
}

int32_t wind_3d_bias(float*       cond,
                     const float* wind3d,
                     int32_t      n,
                     float        k,
                     float        lo,
                     float        hi) noexcept {
    if (cond == nullptr || wind3d == nullptr || n <= 0) return 0;
    if (hi < lo) return 0;

    int32_t clamped = 0;
    for (int32_t i = 0; i < n; ++i) {
        const size_t tri = static_cast<size_t>(i) * 3u;
        const float wx = wind3d[tri + 0];
        const float wy = wind3d[tri + 1];
        const float wz = wind3d[tri + 2];

        bool voxel_clamped = false;
        for (int32_t d = 0; d < 6; ++d) {
            const float dot_w = kDirX[d] * wx + kDirY[d] * wy + kDirZ[d] * wz;
            const float raw   = 1.0f + k * dot_w;
            const float cl    = clampf(raw, lo, hi);
            voxel_clamped |= (raw != cl);
            cond[static_cast<size_t>(d) * static_cast<size_t>(n)
                  + static_cast<size_t>(i)] *= cl;
        }
        clamped += static_cast<int32_t>(voxel_clamped);
    }
    return clamped;
}

}  /* namespace pfsf::aug */
