/**
 * @file morton.cpp
 * @brief 3D Morton (Z-order) encode / decode and tiled-layout builder.
 *
 * @cite Morton Z-order encoding (1966) — Morton, G.M. "A computer-oriented
 *        geodetic data base and a new technique in file sequencing."
 *        IBM Germany Scientific Symposium Series.
 * @formula expandBits(v) spaces v's 10 low bits every 3 positions;
 *          encode(x,y,z) = expand(x) | expand(y)<<1 | expand(z)<<2
 * @maps_to MortonCode.java:encode()/decodeX()/decodeY()/decodeZ()
 *          L19-L56 — bit-exact mirror.
 * @since v0.3d Phase 3
 *
 * Coordinate range: 10 bits per axis (0..1023). Morton code is 30 bits
 * so it fits uint32_t.
 */

#include "pfsf/pfsf_compute.h"

#include <cstdint>
#include <cstring>

namespace {

inline uint32_t expand_bits(uint32_t v) {
    v &= 0x3FFu;                              /* 10 bits */
    v = (v | (v << 16)) & 0x030000FFu;
    v = (v | (v <<  8)) & 0x0300F00Fu;
    v = (v | (v <<  4)) & 0x030C30C3u;
    v = (v | (v <<  2)) & 0x09249249u;
    return v;
}

inline uint32_t compact_bits(uint32_t v) {
    v &= 0x09249249u;
    v = (v | (v >>  2)) & 0x030C30C3u;
    v = (v | (v >>  4)) & 0x0300F00Fu;
    v = (v | (v >>  8)) & 0x030000FFu;
    v = (v | (v >> 16)) & 0x3FFu;
    return v;
}

} // namespace

extern "C" uint32_t pfsf_morton_encode(uint32_t x, uint32_t y, uint32_t z) {
    return expand_bits(x) | (expand_bits(y) << 1) | (expand_bits(z) << 2);
}

extern "C" void pfsf_morton_decode(uint32_t code,
                                     uint32_t* x, uint32_t* y, uint32_t* z) {
    if (x) *x = compact_bits(code);
    if (y) *y = compact_bits(code >> 1);
    if (z) *z = compact_bits(code >> 2);
}

/**
 * @brief Re-lay a linear float array into a tiled cache-blocking layout.
 *
 * @maps_to (new in v0.3d — no prior Java counterpart; fixture-gated)
 *
 * Input: linear[i = x + lx*(y + ly*z)] float per voxel.
 * Output: out[tile_id * tile^3 + intra_tile_linear_idx] — tiles visited
 *         in (tx, ty, tz) row-major order; within a tile the voxels use
 *         (ix, iy, iz) row-major order. Tile size defaults to 8.
 *
 * Used by Phase 3 to land RBGS shared-memory tile loads with 8×8×8 blocks.
 * Phase 3a only implements the tile=8 path; any other value is treated
 * as a no-op to avoid masking a wiring bug.
 */
extern "C" void pfsf_tiled_layout_build(const float* linear,
                                          int32_t lx, int32_t ly, int32_t lz,
                                          int32_t tile,
                                          float* out) {
    if (!linear || !out) return;
    if (lx <= 0 || ly <= 0 || lz <= 0) return;
    if (tile <= 0) return;
    /* Phase 3 scope: only the 8³ tile is exercised by callers today. */
    if (tile != 8) return;

    const int32_t ntx = (lx + tile - 1) / tile;
    const int32_t nty = (ly + tile - 1) / tile;
    const int32_t ntz = (lz + tile - 1) / tile;
    const int32_t tile3 = tile * tile * tile;

    /* Zero-fill in case source dims are not tile-aligned — trailing
     * voxels inside each tile would otherwise carry garbage and confuse
     * downstream shaders. */
    const int64_t outElems = static_cast<int64_t>(ntx) * nty * ntz * tile3;
    std::memset(out, 0, static_cast<size_t>(outElems) * sizeof(float));

    for (int32_t tz = 0; tz < ntz; ++tz) {
    for (int32_t ty = 0; ty < nty; ++ty) {
    for (int32_t tx = 0; tx < ntx; ++tx) {
        const int64_t tileBase =
            (static_cast<int64_t>(tz) * nty * ntx
             + static_cast<int64_t>(ty) * ntx
             + tx) * tile3;
        for (int32_t iz = 0; iz < tile; ++iz) {
            int32_t gz = tz * tile + iz; if (gz >= lz) continue;
            for (int32_t iy = 0; iy < tile; ++iy) {
                int32_t gy = ty * tile + iy; if (gy >= ly) continue;
                for (int32_t ix = 0; ix < tile; ++ix) {
                    int32_t gx = tx * tile + ix; if (gx >= lx) continue;
                    int64_t srcIdx = static_cast<int64_t>(gx)
                        + static_cast<int64_t>(lx)
                          * (static_cast<int64_t>(gy)
                             + static_cast<int64_t>(ly) * gz);
                    int64_t dstIdx = tileBase
                        + (static_cast<int64_t>(iz) * tile + iy) * tile + ix;
                    out[dstIdx] = linear[srcIdx];
                }
            }
        }
    }}}
}
