#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Failure Scan — 斷裂偵測
//  掃描 phi[] 和通量，寫入 fail_flags[]
//  參考：PFSF 手冊 §4.2, §2.3
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint  Lx, Ly, Lz;     // grid dimensions (for neighbor indexing)
    float phi_orphan;      // threshold for no-anchor-path detection (~1e6)
} pc;

layout(set = 0, binding = 0) readonly buffer Phi      { float phi[];       };
layout(set = 0, binding = 1) readonly buffer Sigma    { float sigma[];     };
layout(set = 0, binding = 2) readonly buffer MaxPhi   { float maxPhi[];    }; // per-voxel material limit
layout(set = 0, binding = 3) readonly buffer Rcomp    { float rcomp[];     }; // MPa
layout(set = 0, binding = 4) readonly buffer VType    { uint  vtype[];     };
layout(set = 0, binding = 5) buffer   FailFlags       { uint  fail_flags[]; };

uint idx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

void main() {
    uint i = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (i >= N) return;

    fail_flags[i] = 0u;

    // Skip non-solid voxels
    if (vtype[i] != 1u) return;

    float p = phi[i];

    // ─── Cantilever / Orphan check (§2.3) ───
    // phi > maxPhi → structural limit exceeded
    if (p > maxPhi[i]) {
        // Distinguish orphan (no anchor path) from cantilever
        fail_flags[i] = (p > pc.phi_orphan) ? 3u : 1u;
        return;
    }

    // ─── Crush check: sum inward flux (§2.3) ───
    // flux_in = Σ_{j: φ_j > φ_i} σ_ij × (φ_j - φ_i)
    // if flux_in > Rcomp × BLOCK_AREA × 1e6 → CRUSHING

    if (rcomp[i] <= 0.0) return;

    // Recover 3D coordinates from flat index
    uint x = i % pc.Lx;
    uint rem = i / pc.Lx;
    uint y = rem % pc.Ly;
    uint z = rem / pc.Ly;

    // 6 neighbors with boundary clamping
    uint nx[6] = uint[6](
        x > 0u         ? x - 1u : x,
        x + 1u < pc.Lx ? x + 1u : x,
        x, x, x, x
    );
    uint ny[6] = uint[6](
        y, y,
        y > 0u         ? y - 1u : y,
        y + 1u < pc.Ly ? y + 1u : y,
        y, y
    );
    uint nz[6] = uint[6](
        z, z, z, z,
        z > 0u         ? z - 1u : z,
        z + 1u < pc.Lz ? z + 1u : z
    );

    float flux_in = 0.0;
    for (int d = 0; d < 6; d++) {
        // B2-fix: SoA layout
        uint N_total = pc.Lx * pc.Ly * pc.Lz;
        float s = sigma[d * N_total + i];
        if (s > 0.0) {
            uint j = idx(nx[d], ny[d], nz[d]);
            // Only count inward flux: neighbors with higher phi push load INTO this voxel
            float dphi = phi[j] - p;
            if (dphi > 0.0) {
                flux_in += s * dphi;
            }
        }
    }

    // Rcomp in MPa × 1e6 = Pa; × 1m² block area = N (capacity in Newtons)
    float capacity = rcomp[i] * 1e6;
    if (flux_in > capacity) {
        fail_flags[i] = 2u;  // CRUSHING
    }
}
