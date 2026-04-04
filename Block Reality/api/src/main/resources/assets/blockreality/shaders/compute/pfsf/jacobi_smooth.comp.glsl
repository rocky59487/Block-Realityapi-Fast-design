#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Jacobi + Chebyshev Smooth — 核心迭代器
//  每 GPU tick 更新勢場 φ：讀 phiPrev[]，寫 phi[]
//
//  修正清單：
//  - A1: 呼叫端每步後 swapPhi()（Java 端）
//  - B3: NaN/Inf 防護 clamp
//  - B4: 孤立體素 → 直接標記 NO_SUPPORT（phi=1e7）
//  - B5: 邊界排除（非 clamp 到自身）
//
//  參考：PFSF 手冊 §4.1
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;
    float omega;
    float rho_spec;
    uint  iter;
} pc;

layout(set = 0, binding = 0) buffer PhiCurrent  { float phi[];     };
layout(set = 0, binding = 1) buffer PhiPrev     { float phiPrev[]; };
layout(set = 0, binding = 2) readonly buffer Source { float rho[];  };
layout(set = 0, binding = 3) readonly buffer Cond   { float sigma[]; };
layout(set = 0, binding = 4) readonly buffer Type   { uint  vtype[]; };

uint idx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    uint z = gl_GlobalInvocationID.z;

    if (x >= pc.Lx || y >= pc.Ly || z >= pc.Lz) return;

    uint i = idx(x, y, z);

    // Anchor = Dirichlet BC: phi always 0
    if (vtype[i] == 2u) { phi[i] = 0.0; return; }
    // Air: skip
    if (vtype[i] == 0u) { phi[i] = 0.0; return; }

    // ─── B5-fix: 邊界有效性（排除超界鄰居，非 clamp 到自身）───
    bool valid[6] = bool[6](
        x > 0u,              // -X
        x + 1u < pc.Lx,     // +X
        y > 0u,              // -Y
        y + 1u < pc.Ly,     // +Y
        z > 0u,              // -Z
        z + 1u < pc.Lz      // +Z
    );

    // B5-fix: 只有有效方向才計算鄰居索引
    uint nx[6] = uint[6](x - 1u, x + 1u, x, x, x, x);
    uint ny[6] = uint[6](y, y, y - 1u, y + 1u, y, y);
    uint nz[6] = uint[6](z, z, z, z, z - 1u, z + 1u);

    float sumSigma = 0.0;
    float sumNeighbor = 0.0;

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;  // B5-fix: 跳過超界方向

        float s = sigma[i * 6 + d];
        if (s > 0.0) {
            uint j = idx(nx[d], ny[d], nz[d]);
            float neighborPhi = phiPrev[j];

            // B3-fix: NaN 防護 — 忽略 NaN 鄰居
            if (!isnan(neighborPhi) && !isinf(neighborPhi)) {
                sumSigma    += s;
                sumNeighbor += s * neighborPhi;
            }
        }
    }

    float phi_jacobi;
    if (sumSigma > 0.0) {
        phi_jacobi = (rho[i] + sumNeighbor) / sumSigma;
    } else {
        // B4-fix: 孤立體素（無傳導路徑）→ 直接標記為極高勢能
        // 會被 failure_scan 捕獲為 NO_SUPPORT
        phi_jacobi = 1e7;
    }

    // ─── Chebyshev extrapolation ───
    float result = pc.omega * (phi_jacobi - phiPrev[i]) + phiPrev[i];

    // B3-fix: 結果 clamp + NaN 熔斷
    result = clamp(result, 0.0, 1e7);
    if (isnan(result)) result = phiPrev[i];  // 回退到上一步值

    phi[i] = result;
}
