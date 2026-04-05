#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Failure Scan — 斷裂偵測（各向異性版）
//  新增 TENSION_BREAK：outward flux 超過抗拉強度
//  參考：PFSF 手冊 §4.2, §2.3 + 各向異性 capacity 提案
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint  Lx, Ly, Lz;
    float phi_orphan;      // ~1e6
} pc;

layout(set = 0, binding = 0) readonly buffer Phi      { float phi[];       };
layout(set = 0, binding = 1) readonly buffer Sigma    { float sigma[];     };
layout(set = 0, binding = 2) readonly buffer MaxPhi   { float maxPhi[];    };
layout(set = 0, binding = 3) readonly buffer Rcomp    { float rcomp[];     }; // MPa
layout(set = 0, binding = 4) readonly buffer VType    { uint  vtype[];     };
layout(set = 0, binding = 5) buffer   FailFlags       { uint  fail_flags[]; };
layout(set = 0, binding = 6) readonly buffer Rtens    { float rtens[];     }; // MPa（各向異性）

uint idx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

void main() {
    uint i = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (i >= N) return;

    fail_flags[i] = 0u;

    if (vtype[i] != 1u) return;

    float p = phi[i];

    // ─── Cantilever / Orphan ───
    if (p > maxPhi[i]) {
        fail_flags[i] = (p > pc.phi_orphan) ? 3u : 1u;
        return;
    }

    // Recover 3D coordinates
    uint x = i % pc.Lx;
    uint rem = i / pc.Lx;
    uint y = rem % pc.Ly;
    uint z = rem / pc.Ly;

    // B5-fix: validity check
    bool valid[6] = bool[6](
        x > 0u, x + 1u < pc.Lx,
        y > 0u, y + 1u < pc.Ly,
        z > 0u, z + 1u < pc.Lz
    );

    uint nx[6] = uint[6](x - 1u, x + 1u, x, x, x, x);
    uint ny[6] = uint[6](y, y, y - 1u, y + 1u, y, y);
    uint nz[6] = uint[6](z, z, z, z, z - 1u, z + 1u);

    float flux_in = 0.0;   // 壓力：鄰居 φ > 本體 → 荷載流入
    float flux_out = 0.0;  // 拉力：本體 φ > 鄰居 → 荷載流出

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;

        float s = sigma[d * N + i];  // SoA layout
        if (s > 0.0) {
            uint j = idx(nx[d], ny[d], nz[d]);
            float dphi = phi[j] - p;

            if (dphi > 0.0) {
                flux_in += s * dphi;    // 壓力方向
            } else if (dphi < 0.0) {
                flux_out += s * (-dphi); // 拉力方向
            }
        }
    }

    // ─── Crush check（壓碎）───
    if (rcomp[i] > 0.0) {
        float compCapacity = rcomp[i] * 1e6;
        if (flux_in > compCapacity) {
            fail_flags[i] = 2u;  // CRUSHING
            return;
        }
    }

    // ─── Tension check（拉力斷裂）— 各向異性 capacity ───
    if (rtens[i] > 0.0) {
        float tensCapacity = rtens[i] * 1e6;
        if (flux_out > tensCapacity) {
            fail_flags[i] = 4u;  // TENSION_BREAK
            return;
        }
    }
}
