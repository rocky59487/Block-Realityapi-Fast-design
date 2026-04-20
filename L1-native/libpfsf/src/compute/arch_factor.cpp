/**
 * @file arch_factor.cpp
 * @brief Dual-path BFS for the §2.5.2 ArchFactor map.
 *
 * @algorithm Dual-source BFS building a ratio map over the two largest
 *       anchor-connected groups — bit-exact mirror of
 *       PFSFSourceBuilder.java:computeArchFactorMap().
 * @formula archFactor(v) = min(dA,dB) / max(dA,dB) for voxels reachable
 *          from the two largest horizontally-connected anchor groups;
 *          0 otherwise.
 * @maps_to PFSFSourceBuilder.java:computeArchFactorMap() L88-L146
 * @since v0.3d Phase 2
 */

#include "pfsf/pfsf_compute.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <utility>
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

// ── Path-compressed union-find keyed on flat voxel index ─────────────
class UnionFind {
public:
    explicit UnionFind(int64_t n) : parent_(static_cast<size_t>(n)) {
        for (int64_t i = 0; i < n; ++i) parent_[static_cast<size_t>(i)] = i;
    }
    int64_t find(int64_t x) {
        while (parent_[static_cast<size_t>(x)] != x) {
            int64_t p = parent_[static_cast<size_t>(x)];
            parent_[static_cast<size_t>(x)] = parent_[static_cast<size_t>(p)];
            x = parent_[static_cast<size_t>(x)];
        }
        return x;
    }
    void unite(int64_t a, int64_t b) {
        int64_t ra = find(a);
        int64_t rb = find(b);
        if (ra != rb) parent_[static_cast<size_t>(ra)] = rb;
    }
private:
    std::vector<int64_t> parent_;
};

// BFS over 6-connected neighbours from every voxel in `sources`.
void bfs_6conn(const std::vector<int64_t>& sources,
               const uint8_t* members,
               int32_t lx, int32_t ly, int32_t lz,
               std::vector<int32_t>& out_dist) {
    constexpr int32_t UNREACHED = std::numeric_limits<int32_t>::max();
    std::fill(out_dist.begin(), out_dist.end(), UNREACHED);

    std::vector<int64_t> frontier;
    frontier.reserve(sources.size() + 64);
    for (int64_t s : sources) {
        out_dist[static_cast<size_t>(s)] = 0;
        frontier.push_back(s);
    }

    const int32_t dx[6] = { -1, 1,  0, 0,  0, 0 };
    const int32_t dy[6] = {  0, 0, -1, 1,  0, 0 };
    const int32_t dz[6] = {  0, 0,  0, 0, -1, 1 };

    size_t head = 0;
    while (head < frontier.size()) {
        int64_t cur = frontier[head++];
        int32_t cz = static_cast<int32_t>(cur / (static_cast<int64_t>(lx) * ly));
        int64_t rem = cur - static_cast<int64_t>(cz) * lx * ly;
        int32_t cy = static_cast<int32_t>(rem / lx);
        int32_t cx = static_cast<int32_t>(rem - static_cast<int64_t>(cy) * lx);
        int32_t curDist = out_dist[static_cast<size_t>(cur)];

        for (int k = 0; k < 6; ++k) {
            int32_t nx = cx + dx[k], ny = cy + dy[k], nz = cz + dz[k];
            if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
            int64_t nb = flat_index(nx, ny, nz, lx, ly);
            if (!members[nb]) continue;
            if (out_dist[static_cast<size_t>(nb)] != UNREACHED) continue;
            out_dist[static_cast<size_t>(nb)] = curDist + 1;
            frontier.push_back(nb);
        }
    }
}

} // namespace

extern "C" pfsf_result pfsf_compute_arch_factor_map(const uint8_t* members,
                                                      const uint8_t* anchors,
                                                      int32_t lx, int32_t ly, int32_t lz,
                                                      float* out_arch) {
    if (!members || !anchors || !out_arch) return PFSF_ERROR_INVALID_ARG;
    if (lx <= 0 || ly <= 0 || lz <= 0) return PFSF_ERROR_INVALID_ARG;

    const int64_t N = static_cast<int64_t>(lx) * ly * lz;
    std::memset(out_arch, 0, static_cast<size_t>(N) * sizeof(float));

    // Collect anchors that are also members — mirrors the Java
    // `if (islandMembers.contains(anchor))` guard.
    std::vector<int64_t> anchorIdx;
    anchorIdx.reserve(128);
    for (int64_t i = 0; i < N; ++i) {
        if (anchors[i] && members[i]) anchorIdx.push_back(i);
    }
    if (anchorIdx.size() < 2) return PFSF_OK;

    // Horizontal connectivity union-find over anchors.
    UnionFind uf(N);
    for (int64_t a : anchorIdx) {
        int32_t az = static_cast<int32_t>(a / (static_cast<int64_t>(lx) * ly));
        int64_t rem = a - static_cast<int64_t>(az) * lx * ly;
        int32_t ay = static_cast<int32_t>(rem / lx);
        int32_t ax = static_cast<int32_t>(rem - static_cast<int64_t>(ay) * lx);
        const int32_t hx[4] = { -1, 1, 0, 0 };
        const int32_t hz[4] = { 0, 0, -1, 1 };
        for (int k = 0; k < 4; ++k) {
            int32_t nx = ax + hx[k], nz = az + hz[k];
            if (nx < 0 || nx >= lx || nz < 0 || nz >= lz) continue;
            int64_t nb = flat_index(nx, ay, nz, lx, ly);
            if (anchors[nb] && members[nb]) uf.unite(a, nb);
        }
    }

    // Bucket anchors by root; skip if only one root.
    std::vector<std::pair<int64_t, std::vector<int64_t>>> buckets;
    for (int64_t a : anchorIdx) {
        int64_t r = uf.find(a);
        bool placed = false;
        for (auto& kv : buckets) {
            if (kv.first == r) { kv.second.push_back(a); placed = true; break; }
        }
        if (!placed) buckets.emplace_back(r, std::vector<int64_t>{a});
    }
    if (buckets.size() < 2) return PFSF_OK;

    // Two largest buckets — matches Java's descending sort + take(2).
    std::sort(buckets.begin(), buckets.end(),
              [](const auto& a, const auto& b) {
                  return a.second.size() > b.second.size();
              });
    const auto& groupA = buckets[0].second;
    const auto& groupB = buckets[1].second;

    std::vector<int32_t> distA(static_cast<size_t>(N));
    std::vector<int32_t> distB(static_cast<size_t>(N));
    bfs_6conn(groupA, members, lx, ly, lz, distA);
    bfs_6conn(groupB, members, lx, ly, lz, distB);

    constexpr int32_t UNREACHED = std::numeric_limits<int32_t>::max();
    for (int64_t i = 0; i < N; ++i) {
        if (!members[i]) continue;
        int32_t dA = distA[static_cast<size_t>(i)];
        int32_t dB = distB[static_cast<size_t>(i)];
        if (dA == UNREACHED || dB == UNREACHED) continue;
        float fa = static_cast<float>(dA);
        float fb = static_cast<float>(dB);
        float shorter = fa < fb ? fa : fb;
        float longer  = fa < fb ? fb : fa;
        if (longer > 0.0f) {
            out_arch[i] = shorter / longer;
        } else {
            // Java: archFactorMap.put(pos, 1.0) — both distances equal zero
            // (only possible at anchor voxels belonging to both groups,
            // which cannot happen post-union-find, but preserved verbatim).
            out_arch[i] = 1.0f;
        }
    }
    return PFSF_OK;
}
