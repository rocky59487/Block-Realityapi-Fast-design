/**
 * @file phantom_edges.cpp
 * @brief Diagonal phantom edge injection into SoA-6 conductivity.
 *
 * @cite Internal algorithm — equivalent of
 *       PFSFSourceBuilder.java:injectDiagonalPhantomEdges().
 * @formula σ_phantom(edge) = min(rcomp[a], rcomp[b]) · edge_penalty
 *          σ_phantom(corner) = min(rcomp[a], rcomp[b]) · corner_penalty
 *          written into conductivity[dir*N + flatIdx(a)] only if that slot
 *          is still zero (face-connected pairs take precedence).
 * @maps_to PFSFSourceBuilder.java:injectDiagonalPhantomEdges() L262-L346
 * @since v0.3d Phase 2
 *
 * The Java code passes materialLookup through to fetch rcomp on demand;
 * the C API accepts the already-resolved rcomp array because the caller
 * has the normalised values handy at call time. PFSFDataBuilder owns the
 * conversion and it stays trivial.
 *
 * Direction indices (matching pfsf_direction): NEG_X=0, POS_X=1,
 * NEG_Y=2, POS_Y=3, NEG_Z=4, POS_Z=5.
 */

#include "pfsf/pfsf_compute.h"

#include <cstdint>

namespace {

// 12 edge-adjacent offsets (|dx|+|dy|+|dz| == 2) — mirrors
// PFSFSourceBuilder.EDGE_OFFSETS exactly, in the same order.
constexpr int8_t EDGE_OFFSETS[12][3] = {
    { 1, 1, 0}, { 1,-1, 0}, {-1, 1, 0}, {-1,-1, 0},
    { 1, 0, 1}, { 1, 0,-1}, {-1, 0, 1}, {-1, 0,-1},
    { 0, 1, 1}, { 0, 1,-1}, { 0,-1, 1}, { 0,-1,-1},
};

// 8 corner-adjacent offsets — mirrors PFSFSourceBuilder.CORNER_OFFSETS.
constexpr int8_t CORNER_OFFSETS[8][3] = {
    { 1, 1, 1}, { 1, 1,-1}, { 1,-1, 1}, { 1,-1,-1},
    {-1, 1, 1}, {-1, 1,-1}, {-1,-1, 1}, {-1,-1,-1},
};

inline int64_t flat_index(int32_t x, int32_t y, int32_t z,
                          int32_t lx, int32_t ly) {
    return static_cast<int64_t>(x)
         + static_cast<int64_t>(lx) *
           (static_cast<int64_t>(y)
            + static_cast<int64_t>(ly) *
              static_cast<int64_t>(z));
}

// Java SourceBuilder preference: first non-zero axis in offset picks the
// injection direction. Returns one of pfsf_direction values or -1.
inline int32_t pick_dir_for_edge(int8_t ox, int8_t oy, int8_t oz) {
    if (ox != 0) return ox > 0 ? 1 : 0;   // POS_X : NEG_X
    if (oy != 0) return oy > 0 ? 3 : 2;   // POS_Y : NEG_Y
    if (oz != 0) return oz > 0 ? 5 : 4;   // POS_Z : NEG_Z
    return -1;
}

// Corner branch: Java always uses X axis regardless of offset axes
// present — `offset[0] > 0 ? DIR_POS_X : DIR_NEG_X`. Preserved verbatim.
inline int32_t pick_dir_for_corner(int8_t ox) {
    return ox > 0 ? 1 : 0;
}

} // namespace

extern "C" int32_t pfsf_inject_phantom_edges(const uint8_t* members,
                                               float* conductivity,
                                               const float* rcomp,
                                               int32_t lx, int32_t ly, int32_t lz,
                                               float edge_penalty,
                                               float corner_penalty) {
    if (!members || !conductivity || !rcomp) return 0;
    if (lx <= 0 || ly <= 0 || lz <= 0) return 0;

    const int64_t N = static_cast<int64_t>(lx) * ly * lz;
    int32_t injected = 0;

    for (int32_t z = 0; z < lz; ++z) {
        for (int32_t y = 0; y < ly; ++y) {
            for (int32_t x = 0; x < lx; ++x) {
                int64_t self = flat_index(x, y, z, lx, ly);
                if (!members[self]) continue;

                // ─── edge-adjacent (12) ─────────────────────────────
                for (int k = 0; k < 12; ++k) {
                    int8_t ox = EDGE_OFFSETS[k][0];
                    int8_t oy = EDGE_OFFSETS[k][1];
                    int8_t oz = EDGE_OFFSETS[k][2];
                    int32_t nx = x + ox, ny = y + oy, nz = z + oz;
                    if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
                    int64_t nb = flat_index(nx, ny, nz, lx, ly);
                    if (!members[nb]) continue;
                    // Java's hasFaceConnection branch is always false for
                    // |offset|>=2; skipping it preserves semantics.
                    int32_t dirIdx = pick_dir_for_edge(ox, oy, oz);
                    if (dirIdx < 0) continue;
                    float ra = rcomp[self];
                    float rb = rcomp[nb];
                    float base = (ra < rb ? ra : rb) * edge_penalty;
                    int64_t slot = static_cast<int64_t>(dirIdx) * N + self;
                    if (conductivity[slot] == 0.0f) {
                        conductivity[slot] = base;
                        ++injected;
                    }
                }

                // ─── corner-adjacent (8) ────────────────────────────
                for (int k = 0; k < 8; ++k) {
                    int8_t ox = CORNER_OFFSETS[k][0];
                    int8_t oy = CORNER_OFFSETS[k][1];
                    int8_t oz = CORNER_OFFSETS[k][2];
                    int32_t nx = x + ox, ny = y + oy, nz = z + oz;
                    if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
                    int64_t nb = flat_index(nx, ny, nz, lx, ly);
                    if (!members[nb]) continue;
                    int32_t dirIdx = pick_dir_for_corner(ox);
                    float ra = rcomp[self];
                    float rb = rcomp[nb];
                    float base = (ra < rb ? ra : rb) * corner_penalty;
                    int64_t slot = static_cast<int64_t>(dirIdx) * N + self;
                    if (conductivity[slot] == 0.0f) {
                        conductivity[slot] = base;
                        ++injected;
                    }
                }
            }
        }
    }
    return injected;
}
