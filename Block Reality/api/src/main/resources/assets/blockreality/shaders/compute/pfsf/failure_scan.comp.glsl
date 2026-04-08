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
// Macro-block residual output — per-8³-block max |Δφ|，供 rbgs_smooth 跳過已收斂區塊
// 使用 uint 做 atomicMax（正浮點數 IEEE-754 位元表示保持單調）
layout(set = 0, binding = 7) buffer MacroResidual     { uint macroResidualBits[]; };
layout(set = 0, binding = 8) readonly buffer Source   { float rho[]; };

const uint MACRO_BLOCK_SIZE = 8u;

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

    // ─── 12 edge neighbors + 8 corner neighbors (26-connectivity) ───
    int igx = int(x), igy = int(y), igz = int(z);

    // 安全讀取 phi，越界回傳 0
    // 但在 failure scan 中，超出邊界代表沒有 neighbor 貢獻。這裡可以使用 valid 檢查
    float sx_neg = sigma[0 * N + i]; float sx_pos = sigma[1 * N + i];
    float sy_neg = sigma[2 * N + i]; float sy_pos = sigma[3 * N + i];
    float sz_neg = sigma[4 * N + i]; float sz_pos = sigma[5 * N + i];
    float sx_avg = (sx_neg + sx_pos) * 0.5;
    float sy_avg = (sy_neg + sy_pos) * 0.5;
    float sz_avg = (sz_neg + sz_pos) * 0.5;
    const float EDGE_P   = 0.35;
    const float CORNER_P = 0.15;

    // 12 edge neighbors
    float edgeSigmaXY = sqrt(max(sx_avg * sy_avg, 0.0)) * EDGE_P;
    if (edgeSigmaXY > 0.0) {
        if (valid[0] && valid[2]) { float dphi = phi[idx(x-1u,y-1u,z)] - p; if (dphi > 0.0) flux_in += edgeSigmaXY * dphi; else flux_out += edgeSigmaXY * (-dphi); }
        if (valid[1] && valid[3]) { float dphi = phi[idx(x+1u,y+1u,z)] - p; if (dphi > 0.0) flux_in += edgeSigmaXY * dphi; else flux_out += edgeSigmaXY * (-dphi); }
        if (valid[0] && valid[3]) { float dphi = phi[idx(x-1u,y+1u,z)] - p; if (dphi > 0.0) flux_in += edgeSigmaXY * dphi; else flux_out += edgeSigmaXY * (-dphi); }
        if (valid[1] && valid[2]) { float dphi = phi[idx(x+1u,y-1u,z)] - p; if (dphi > 0.0) flux_in += edgeSigmaXY * dphi; else flux_out += edgeSigmaXY * (-dphi); }
    }
    float edgeSigmaXZ = sqrt(max(sx_avg * sz_avg, 0.0)) * EDGE_P;
    if (edgeSigmaXZ > 0.0) {
        if (valid[0] && valid[4]) { float dphi = phi[idx(x-1u,y,z-1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaXZ * dphi; else flux_out += edgeSigmaXZ * (-dphi); }
        if (valid[1] && valid[5]) { float dphi = phi[idx(x+1u,y,z+1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaXZ * dphi; else flux_out += edgeSigmaXZ * (-dphi); }
        if (valid[0] && valid[5]) { float dphi = phi[idx(x-1u,y,z+1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaXZ * dphi; else flux_out += edgeSigmaXZ * (-dphi); }
        if (valid[1] && valid[4]) { float dphi = phi[idx(x+1u,y,z-1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaXZ * dphi; else flux_out += edgeSigmaXZ * (-dphi); }
    }
    float edgeSigmaYZ = sqrt(max(sy_avg * sz_avg, 0.0)) * EDGE_P;
    if (edgeSigmaYZ > 0.0) {
        if (valid[2] && valid[4]) { float dphi = phi[idx(x,y-1u,z-1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaYZ * dphi; else flux_out += edgeSigmaYZ * (-dphi); }
        if (valid[3] && valid[5]) { float dphi = phi[idx(x,y+1u,z+1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaYZ * dphi; else flux_out += edgeSigmaYZ * (-dphi); }
        if (valid[2] && valid[5]) { float dphi = phi[idx(x,y-1u,z+1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaYZ * dphi; else flux_out += edgeSigmaYZ * (-dphi); }
        if (valid[3] && valid[4]) { float dphi = phi[idx(x,y+1u,z-1u)] - p; if (dphi > 0.0) flux_in += edgeSigmaYZ * dphi; else flux_out += edgeSigmaYZ * (-dphi); }
    }

    // 8 corner neighbors
    float cornerSigma = pow(max(sx_avg * sy_avg * sz_avg, 0.0), 1.0/3.0) * CORNER_P;
    if (cornerSigma > 0.0) {
        int dxc[2] = int[2](-1, 1);
        int dyc[2] = int[2](-1, 1);
        int dzc[2] = int[2](-1, 1);
        for (int ci = 0; ci < 8; ci++) {
            int cx = dxc[ci & 1], cy = dyc[(ci >> 1) & 1], cz = dzc[(ci >> 2) & 1];
            int nx_i = igx + cx, ny_i = igy + cy, nz_i = igz + cz;
            if (nx_i >= 0 && nx_i < int(pc.Lx) && ny_i >= 0 && ny_i < int(pc.Ly) && nz_i >= 0 && nz_i < int(pc.Lz)) {
                float dphi = phi[idx(uint(nx_i), uint(ny_i), uint(nz_i))] - p;
                if (dphi > 0.0) flux_in += cornerSigma * dphi;
                else flux_out += cornerSigma * (-dphi);
            }
        }
    }

    // ─── Crush check（壓碎）───
    // D1-fix: 移除 ×1e6。rcomp/rtens 已在 CPU 端與 sigma 同步正規化，
    // flux 與 capacity 在同一量綱下直接比較。
    if (rcomp[i] > 0.0) {
        if (flux_in > rcomp[i]) {
            fail_flags[i] = 2u;  // CRUSHING
            return;
        }
    }

    // ─── Tension check（拉力斷裂）— 各向異性 capacity ───
    if (rtens[i] > 0.0) {
        if (flux_out > rtens[i]) {
            fail_flags[i] = 4u;  // TENSION_BREAK
            return;
        }
    }

    // ─── Macro-block residual 寫入（供 rbgs_smooth early-exit）───
    // D5-fix: 殘差 = |flux_in - flux_out|，即 |Σ σ×Δφ| 的近似。
    // 平衡態時 flux_in ≈ flux_out → residual → 0。
    // 舊公式 abs(flux_in - flux_out - abs(p)) 混入了 phi 本身，
    // 在 phi 較大的深層體素會產生虛高殘差（phi 不是殘差的一部分）。
    {
        float residual = abs(flux_in - flux_out + rho[i]);
        uint mbx = x / MACRO_BLOCK_SIZE;
        uint mby = y / MACRO_BLOCK_SIZE;
        uint mbz = z / MACRO_BLOCK_SIZE;
        uint mbCountX = (pc.Lx + MACRO_BLOCK_SIZE - 1u) / MACRO_BLOCK_SIZE;
        uint mbCountY = (pc.Ly + MACRO_BLOCK_SIZE - 1u) / MACRO_BLOCK_SIZE;
        uint mbIdx = mbx + mbCountX * (mby + mbCountY * mbz);
        // Atomic max — 每個 macroblock 取最大殘差
        // 正浮點數的 IEEE-754 位元表示保持單調，atomicMax(uint) 等價於 float max
        uint residualBits = floatBitsToUint(residual);
        atomicMax(macroResidualBits[mbIdx], residualBits);
    }
}
