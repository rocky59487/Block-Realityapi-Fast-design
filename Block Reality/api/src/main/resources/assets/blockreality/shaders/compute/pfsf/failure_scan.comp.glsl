#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Failure Scan — 斷裂偵測
//  掃描 phi[] 和通量，寫入 fail_flags[]
//  參考：PFSF 手冊 §4.2, §2.3
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint  N;               // total voxel count
    float phi_orphan;      // threshold for no-anchor-path detection (~1e6)
} pc;

layout(set = 0, binding = 0) readonly buffer Phi      { float phi[];       };
layout(set = 0, binding = 1) readonly buffer Sigma    { float sigma[];     };
layout(set = 0, binding = 2) readonly buffer MaxPhi   { float maxPhi[];    }; // per-voxel material limit
layout(set = 0, binding = 3) readonly buffer Rcomp    { float rcomp[];     }; // MPa
layout(set = 0, binding = 4) readonly buffer VType    { uint  vtype[];     };
layout(set = 0, binding = 5) buffer   FailFlags       { uint  fail_flags[]; };

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.N) return;

    fail_flags[i] = 0u;

    // Skip non-solid voxels
    if (vtype[i] != 1u) return;

    float p = phi[i];

    // ─── Cantilever / Orphan check ───
    // phi > maxPhi → structural limit exceeded
    if (p > maxPhi[i]) {
        // Distinguish orphan (no anchor path) from cantilever
        fail_flags[i] = (p > pc.phi_orphan) ? 3u : 1u;
        return;
    }

    // ─── Crush check ───
    // Simplified: compare phi against compressive capacity
    // Full version would sum inward flux from all neighbors with phi > phi[i]
    if (rcomp[i] > 0.0) {
        // Compute inward flux approximation
        float flux_in = 0.0;
        for (int d = 0; d < 6; d++) {
            float s = sigma[i * 6 + d];
            // We approximate flux by checking if this voxel's phi is high
            // relative to its compressive capacity
            // A more precise version would read neighbor phi values
            flux_in += s;
        }

        // Utilization ratio: phi normalized by material capacity
        // rcomp in MPa, multiply by 1e6 for Pa, then by 1m² block area
        float capacity = rcomp[i] * 1e6;  // Pa × m² = N
        if (capacity > 0.0 && p > 0.0) {
            float utilization = p / capacity;
            if (utilization > 1.0) {
                fail_flags[i] = 2u;  // CRUSHING
            }
        }
    }
}
