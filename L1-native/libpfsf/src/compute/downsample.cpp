/**
 * @file downsample.cpp
 * @brief 2:1 multigrid coarse downsample — majority-vote type + averaged field.
 *
 * @cite Multigrid restriction — Briggs, W.L., Henson, V.E., McCormick, S.F.
 *        (2000). "A Multigrid Tutorial" §3.2.
 * @formula coarse[c] = mean(fine[2x..2x+1, 2y..2y+1, 2z..2z+1])
 *          coarse_type[c] = majority-vote over the same 2×2×2 block;
 *          ties broken toward {ANCHOR > SOLID > AIR}.
 * @maps_to PFSFDataBuilder (planned) — no prior Java counterpart; this
 *          primitive replaces the GPU-side mg_restrict.comp trilinear
 *          fallback for the fixture replay harness.
 * @since v0.3d Phase 3
 *
 * Dimensions are derived from the fine grid: coarse = ceil(fine/2).
 * Out-of-bounds fine voxels contribute neither to the mean nor to the
 * majority vote — this matches the half-cell restriction used by the
 * shader path.
 */

#include "pfsf/pfsf_compute.h"
#include "pfsf/pfsf_types.h"

#include <cstdint>

namespace {

inline int64_t fine_idx(int32_t x, int32_t y, int32_t z,
                        int32_t lx, int32_t ly) {
    return static_cast<int64_t>(x)
         + static_cast<int64_t>(lx) *
           (static_cast<int64_t>(y)
            + static_cast<int64_t>(ly) *
              static_cast<int64_t>(z));
}

} // namespace

extern "C" void pfsf_downsample_2to1(const float* fine,
                                       const uint8_t* fine_type,
                                       int32_t lxf, int32_t lyf, int32_t lzf,
                                       float* coarse,
                                       uint8_t* coarse_type) {
    if (!fine || !coarse) return;
    if (lxf <= 0 || lyf <= 0 || lzf <= 0) return;

    const int32_t lxc = (lxf + 1) / 2;
    const int32_t lyc = (lyf + 1) / 2;
    const int32_t lzc = (lzf + 1) / 2;

    for (int32_t zc = 0; zc < lzc; ++zc) {
    for (int32_t yc = 0; yc < lyc; ++yc) {
    for (int32_t xc = 0; xc < lxc; ++xc) {
        float sum = 0.0f;
        int   contrib = 0;
        int   cnt_air = 0, cnt_solid = 0, cnt_anchor = 0;

        for (int32_t dz = 0; dz < 2; ++dz) {
            int32_t zf = zc * 2 + dz; if (zf >= lzf) continue;
            for (int32_t dy = 0; dy < 2; ++dy) {
                int32_t yf = yc * 2 + dy; if (yf >= lyf) continue;
                for (int32_t dx = 0; dx < 2; ++dx) {
                    int32_t xf = xc * 2 + dx; if (xf >= lxf) continue;
                    int64_t fi = fine_idx(xf, yf, zf, lxf, lyf);
                    sum += fine[fi];
                    ++contrib;
                    if (fine_type) {
                        uint8_t t = fine_type[fi];
                        if      (t == PFSF_VOXEL_ANCHOR) ++cnt_anchor;
                        else if (t == PFSF_VOXEL_SOLID)  ++cnt_solid;
                        else                             ++cnt_air;
                    }
                }
            }
        }

        int64_t ci = static_cast<int64_t>(xc)
                   + static_cast<int64_t>(lxc) *
                     (static_cast<int64_t>(yc)
                      + static_cast<int64_t>(lyc) * zc);
        coarse[ci] = (contrib > 0) ? (sum / static_cast<float>(contrib)) : 0.0f;

        if (coarse_type) {
            /* Priority-rule restriction (anchor > solid > air):
             * any anchor in the 2×2×2 block promotes the coarse voxel to
             * anchor regardless of count, so a structural anchor is never
             * silently lost when coarsening. Only if no anchors exist does
             * the more-common type win; the same rule breaks that tie. */
            if (cnt_anchor > 0) {
                coarse_type[ci] = PFSF_VOXEL_ANCHOR;
            } else if (cnt_solid > 0) {
                coarse_type[ci] = PFSF_VOXEL_SOLID;
            } else {
                coarse_type[ci] = PFSF_VOXEL_AIR;
            }
        }
    }}}
}
