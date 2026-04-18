/**
 * @file arm_map.cpp
 * @brief Horizontal-only multi-source BFS for the §2.4.1 arm map.
 *
 * @algorithm Horizontal-only Manhattan BFS from anchor voxels —
 *       bit-exact mirror of PFSFSourceBuilder.java:computeHorizontalArmMap().
 * @formula arm(v) = min Manhattan distance from any anchor, measured only
 *          along {±X, ±Z}. Vertical edges are excluded on purpose so the
 *          moment factor reflects lateral load transfer.
 * @maps_to PFSFSourceBuilder.java:computeHorizontalArmMap() L41-L72
 * @since v0.3d Phase 2
 */

#include "pfsf/pfsf_compute.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

inline int64_t flat_index(int32_t x, int32_t y, int32_t z,
                          int32_t lx, int32_t ly) {
    return static_cast<int64_t>(x)
         + static_cast<int64_t>(lx) *
           (static_cast<int64_t>(y)
            + static_cast<int64_t>(ly) *
              static_cast<int64_t>(z));
}

} // namespace

extern "C" pfsf_result pfsf_compute_arm_map(const uint8_t* members,
                                              const uint8_t* anchors,
                                              int32_t lx, int32_t ly, int32_t lz,
                                              int32_t* out_arm) {
    if (!members || !anchors || !out_arm) return PFSF_ERROR_INVALID_ARG;
    if (lx <= 0 || ly <= 0 || lz <= 0) return PFSF_ERROR_INVALID_ARG;

    const int64_t N = static_cast<int64_t>(lx) * ly * lz;

    // Initialise: non-members → 0 (matches Java `getOrDefault(pos, 0)`
    // semantics). Members get a sentinel we overwrite during BFS.
    constexpr int32_t UNREACHED = -1;
    for (int64_t i = 0; i < N; ++i) {
        out_arm[i] = members[i] ? UNREACHED : 0;
    }

    // Multi-source BFS frontier — ring buffer of flat indices.
    std::vector<int64_t> frontier;
    frontier.reserve(static_cast<size_t>(N));

    for (int64_t i = 0; i < N; ++i) {
        if (anchors[i] && members[i]) {
            out_arm[i] = 0;
            frontier.push_back(i);
        }
    }

    // Horizontal-only neighbour offsets (no Y): -X, +X, -Z, +Z.
    const int32_t dx[4] = { -1, 1, 0, 0 };
    const int32_t dz[4] = { 0, 0, -1, 1 };

    size_t head = 0;
    while (head < frontier.size()) {
        int64_t cur = frontier[head++];
        int32_t cz = static_cast<int32_t>(cur / (static_cast<int64_t>(lx) * ly));
        int64_t rem = cur - static_cast<int64_t>(cz) * lx * ly;
        int32_t cy = static_cast<int32_t>(rem / lx);
        int32_t cx = static_cast<int32_t>(rem - static_cast<int64_t>(cy) * lx);

        int32_t curArm = out_arm[cur];

        for (int k = 0; k < 4; ++k) {
            int32_t nx = cx + dx[k];
            int32_t nz = cz + dz[k];
            if (nx < 0 || nx >= lx || nz < 0 || nz >= lz) continue;
            int64_t nb = flat_index(nx, cy, nz, lx, ly);
            if (!members[nb]) continue;
            if (out_arm[nb] != UNREACHED) continue;
            out_arm[nb] = curArm + 1;
            frontier.push_back(nb);
        }
    }

    // Collapse remaining UNREACHED members to 0 — mirrors Java's default
    // behaviour where voxels without a horizontal anchor path contribute
    // arm=0 to the moment factor.
    for (int64_t i = 0; i < N; ++i) {
        if (out_arm[i] == UNREACHED) out_arm[i] = 0;
    }
    return PFSF_OK;
}
