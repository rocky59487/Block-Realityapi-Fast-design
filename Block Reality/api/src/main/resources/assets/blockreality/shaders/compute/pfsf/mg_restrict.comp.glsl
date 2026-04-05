#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Multigrid Restriction — 殘差降採樣（細 → 粗）
//  計算殘差 r = rho - L*phi，2×2×2 平均降到粗網格
//  參考：PFSF 手冊 §4.4, McAdams et al. 2010
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PushConstants {
    uint Lx_fine,  Ly_fine,  Lz_fine;
    uint Lx_coarse, Ly_coarse, Lz_coarse;
} pc;

layout(set = 0, binding = 0) readonly buffer PhiFine      { float phi_fine[];   };
layout(set = 0, binding = 1) readonly buffer SourceFine    { float rho_fine[];   };
layout(set = 0, binding = 2) readonly buffer CondFine      { float sigma_fine[]; };
layout(set = 0, binding = 3) readonly buffer TypeFine      { uint  vtype_fine[]; };
layout(set = 0, binding = 4) buffer PhiCoarse              { float phi_coarse[]; };
layout(set = 0, binding = 5) buffer SourceCoarse           { float rho_coarse[]; };

uint idxFine(uint x, uint y, uint z) {
    return x + pc.Lx_fine * (y + pc.Ly_fine * z);
}

uint idxCoarse(uint x, uint y, uint z) {
    return x + pc.Lx_coarse * (y + pc.Ly_coarse * z);
}

// 計算細網格上體素 i 的殘差 r_i = rho_i - L*phi_i
float residual(uint fx, uint fy, uint fz) {
    uint i = idxFine(fx, fy, fz);

    // Air or anchor: residual = 0
    if (vtype_fine[i] != 1u) return 0.0;

    float Lphi = 0.0; // L*phi = sum(sigma_ij * (phi_j - phi_i))
    uint nx[6] = uint[6](
        fx > 0u ? fx - 1u : fx,
        fx + 1u < pc.Lx_fine ? fx + 1u : fx,
        fx, fx, fx, fx
    );
    uint ny[6] = uint[6](
        fy, fy,
        fy > 0u ? fy - 1u : fy,
        fy + 1u < pc.Ly_fine ? fy + 1u : fy,
        fy, fy
    );
    uint nz[6] = uint[6](
        fz, fz, fz, fz,
        fz > 0u ? fz - 1u : fz,
        fz + 1u < pc.Lz_fine ? fz + 1u : fz
    );

    // C1-fix: SoA layout — sigma_fine[d * nFine + i]
    uint nFine = pc.Lx_fine * pc.Ly_fine * pc.Lz_fine;
    for (int d = 0; d < 6; d++) {
        float s = sigma_fine[d * nFine + i];
        if (s > 0.0) {
            uint j = idxFine(nx[d], ny[d], nz[d]);
            Lphi += s * (phi_fine[j] - phi_fine[i]);
        }
    }

    // r = rho - (-Lphi) = rho + Lphi
    // (Poisson: Lphi + rho = 0 → residual = rho + Lphi)
    return rho_fine[i] + Lphi;
}

void main() {
    uint cIdx = gl_GlobalInvocationID.x;
    uint N_coarse = pc.Lx_coarse * pc.Ly_coarse * pc.Lz_coarse;
    if (cIdx >= N_coarse) return;

    // Coarse index → coarse (cx, cy, cz)
    uint cx = cIdx % pc.Lx_coarse;
    uint rem = cIdx / pc.Lx_coarse;
    uint cy = rem % pc.Ly_coarse;
    uint cz = rem / pc.Ly_coarse;

    // Fine grid 2×2×2 block origin
    uint fx0 = cx * 2u;
    uint fy0 = cy * 2u;
    uint fz0 = cz * 2u;

    // Average residual over 2×2×2 block
    float sumResidual = 0.0;
    int count = 0;

    for (uint dz = 0u; dz < 2u; dz++) {
        for (uint dy = 0u; dy < 2u; dy++) {
            for (uint dx = 0u; dx < 2u; dx++) {
                uint fx = fx0 + dx;
                uint fy = fy0 + dy;
                uint fz = fz0 + dz;
                if (fx < pc.Lx_fine && fy < pc.Ly_fine && fz < pc.Lz_fine) {
                    sumResidual += residual(fx, fy, fz);
                    count++;
                }
            }
        }
    }

    // Coarse source = averaged residual × volume ratio (8 fine → 1 coarse)
    rho_coarse[cIdx] = (count > 0) ? sumResidual : 0.0;

    // Initialize coarse phi to average of fine phi
    float sumPhi = 0.0;
    count = 0;
    for (uint dz = 0u; dz < 2u; dz++) {
        for (uint dy = 0u; dy < 2u; dy++) {
            for (uint dx = 0u; dx < 2u; dx++) {
                uint fx = fx0 + dx;
                uint fy = fy0 + dy;
                uint fz = fz0 + dz;
                if (fx < pc.Lx_fine && fy < pc.Ly_fine && fz < pc.Lz_fine) {
                    sumPhi += phi_fine[idxFine(fx, fy, fz)];
                    count++;
                }
            }
        }
    }
    phi_coarse[cIdx] = (count > 0) ? sumPhi / float(count) : 0.0;
}
