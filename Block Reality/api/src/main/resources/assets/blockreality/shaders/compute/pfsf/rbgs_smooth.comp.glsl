#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF RBGS（Red-Black Gauss-Seidel）8 色就地迭代
//
//  v2.1 新增：取代 jacobi_smooth.comp.glsl 的 Jacobi 迭代。
//
//  理論基礎：
//    Young (1971)：ρ(GS) = ρ(J)²，漸近收斂率精確為 Jacobi 的 2 倍。
//    Adams & Ortega (1982)：多色 SOR 在平行計算中的收斂保證。
//
//  8 色 Octree 著色：
//    color = (x%2) | (y%2)<<1 | (z%2)<<2   → 0~7
//    每 colorPass 只更新 color == colorPass 的體素。
//    26-connectivity 下所有鄰居保證為不同顏色，無 Data Race。
//
//  26-connectivity 隱式剪力（繼承自 v2 Phase A+B 設計）：
//    12 edge + 8 corner 鄰居透過幾何平均 sigma 貢獻，
//    係數匹配 PFSFConstants.SHEAR_EDGE_PENALTY / SHEAR_CORNER_PENALTY。
//
//  Workgroup: 256 threads（1D flat，與 WG_RBGS 對齊）
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;   // island grid dimensions
    uint  colorPass;     // 當前顏色 pass（0~7）
    float damping;       // 能量衰減（M1-fix: 0.0=無，0.995=振盪壓制）
} pc;

layout(set = 0, binding = 0) buffer PhiInPlace { float phi[];    };  // 就地讀寫（無 phiPrev）
layout(set = 0, binding = 1) readonly buffer Source { float rho[]; };
layout(set = 0, binding = 2) readonly buffer Cond   { float sigma[]; };
layout(set = 0, binding = 3) readonly buffer Type   { uint  vtype[]; };
// v2.1: Amor 拉壓分裂 — 寫入歷史應變能場
layout(set = 0, binding = 4) buffer HField { float hField[]; };

// 3D 線性全局索引（SoA conductivity layout: sigma[dir*N + i]）
uint gIdx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

// 安全讀取 phi（超界返回 0）
float safePhi(int gx, int gy, int gz) {
    if (gx < 0 || uint(gx) >= pc.Lx ||
        gy < 0 || uint(gy) >= pc.Ly ||
        gz < 0 || uint(gz) >= pc.Lz) return 0.0;
    return phi[gIdx(uint(gx), uint(gy), uint(gz))];
}

void main() {
    uint flatIdx = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (flatIdx >= N) return;

    // 還原 3D 座標
    uint gx = flatIdx % pc.Lx;
    uint rem = flatIdx / pc.Lx;
    uint gy = rem % pc.Ly;
    uint gz = rem / pc.Ly;

    // ─── 8 色著色篩選 ───
    uint color = (gx & 1u) | ((gy & 1u) << 1u) | ((gz & 1u) << 2u);
    if (color != pc.colorPass) return;

    uint i = flatIdx;

    // Anchor 固定 φ=0；Air 跳過
    if (vtype[i] == 2u) { phi[i] = 0.0; return; }
    if (vtype[i] == 0u) { phi[i] = 0.0; return; }

    // ─── 6 鄰居有效性 ───
    bool valid[6] = bool[6](
        gx > 0u, gx + 1u < pc.Lx,
        gy > 0u, gy + 1u < pc.Ly,
        gz > 0u, gz + 1u < pc.Lz
    );

    // ─── Gauss-Seidel：直接讀取 phi[]（就地更新，8 色著色保證 26 鄰域內無同色）───
    int igx = int(gx), igy = int(gy), igz = int(gz);
    float neighborPhi[6] = float[6](
        safePhi(igx - 1, igy, igz),  // -X
        safePhi(igx + 1, igy, igz),  // +X
        safePhi(igx, igy - 1, igz),  // -Y
        safePhi(igx, igy + 1, igz),  // +Y
        safePhi(igx, igy, igz - 1),  // -Z
        safePhi(igx, igy, igz + 1)   // +Z
    );

    float sumSigma   = 0.0;
    float sumNeighbor = 0.0;

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;
        float s = sigma[d * N + i];
        if (s > 0.0) {
            float np = neighborPhi[d];
            if (!isnan(np) && !isinf(np)) {
                sumSigma    += s;
                sumNeighbor += s * np;
            }
        }
    }

    // ─── v2: 隱式 26-connectivity 剪力（Edge + Corner 鄰居）───
    {
        float sx_neg = sigma[0 * N + i]; float sx_pos = sigma[1 * N + i];
        float sy_neg = sigma[2 * N + i]; float sy_pos = sigma[3 * N + i];
        float sz_neg = sigma[4 * N + i]; float sz_pos = sigma[5 * N + i];
        float sx_avg = (sx_neg + sx_pos) * 0.5;
        float sy_avg = (sy_neg + sy_pos) * 0.5;
        float sz_avg = (sz_neg + sz_pos) * 0.5;
        const float EDGE_P   = 0.35;  // MUST match PFSFConstants.SHEAR_EDGE_PENALTY
        const float CORNER_P = 0.15;  // MUST match PFSFConstants.SHEAR_CORNER_PENALTY

        // 12 edge neighbors
        float edgeSigmaXY = sqrt(max(sx_avg * sy_avg, 0.0)) * EDGE_P;
        if (edgeSigmaXY > 0.0) {
            if (valid[0] && valid[2]) { float ep = safePhi(igx-1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXY; sumNeighbor+=edgeSigmaXY*ep; } }
            if (valid[1] && valid[3]) { float ep = safePhi(igx+1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXY; sumNeighbor+=edgeSigmaXY*ep; } }
            if (valid[0] && valid[3]) { float ep = safePhi(igx-1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXY; sumNeighbor+=edgeSigmaXY*ep; } }
            if (valid[1] && valid[2]) { float ep = safePhi(igx+1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXY; sumNeighbor+=edgeSigmaXY*ep; } }
        }
        float edgeSigmaXZ = sqrt(max(sx_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaXZ > 0.0) {
            if (valid[0] && valid[4]) { float ep = safePhi(igx-1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[1] && valid[5]) { float ep = safePhi(igx+1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[0] && valid[5]) { float ep = safePhi(igx-1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[1] && valid[4]) { float ep = safePhi(igx+1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
        }
        float edgeSigmaYZ = sqrt(max(sy_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaYZ > 0.0) {
            if (valid[2] && valid[4]) { float ep = safePhi(igx,igy-1,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[3] && valid[5]) { float ep = safePhi(igx,igy+1,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[2] && valid[5]) { float ep = safePhi(igx,igy-1,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[3] && valid[4]) { float ep = safePhi(igx,igy+1,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
        }
        // 8 corners
        float cornerSigma = pow(max(sx_avg * sy_avg * sz_avg, 0.0), 1.0/3.0) * CORNER_P;
        if (cornerSigma > 0.0) {
            int dxc[2] = int[2](-1, 1);
            int dyc[2] = int[2](-1, 1);
            int dzc[2] = int[2](-1, 1);
            for (int ci = 0; ci < 8; ci++) {
                int cx = dxc[ci & 1], cy = dyc[(ci >> 1) & 1], cz = dzc[(ci >> 2) & 1];
                int nx = igx + cx, ny = igy + cy, nz = igz + cz;
                if (nx >= 0 && nx < int(pc.Lx) && ny >= 0 && ny < int(pc.Ly) && nz >= 0 && nz < int(pc.Lz)) {
                    float cp = safePhi(nx, ny, nz);
                    if (!isnan(cp) && !isinf(cp)) { sumSigma += cornerSigma; sumNeighbor += cornerSigma * cp; }
                }
            }
        }
    }

    float phi_gs;
    if (sumSigma > 0.0) {
        phi_gs = (rho[i] + sumNeighbor) / sumSigma;
    } else {
        phi_gs = 1e7;  // 孤立體素（B4-fix）
    }

    // ─── v2.1: Amor 啟發式拉壓分裂（與 jacobi_smooth 邏輯一致）───
    {
        float flux_in  = 0.0;
        float flux_out = 0.0;
        for (int d = 0; d < 6; d++) {
            if (!valid[d]) continue;
            float s = sigma[d * N + i];
            if (s <= 0.0) continue;
            float flow = s * (neighborPhi[d] - phi_gs);
            if (flow > 0.0) flux_in  += flow;
            else            flux_out -= flow;
        }
        float k_comp = (flux_in > 3.0 * flux_out) ? 0.01 : 1.0;
        float psi_e  = 0.5 * phi_gs * phi_gs * k_comp;
        hField[i] = max(hField[i], psi_e);  // 不可逆更新歷史場
    }

    // ─── 能量衰減（M1-fix，振盪壓制時啟用）───
    float result = clamp(phi_gs, 0.0, 1e7);
    if (pc.damping > 0.0) result *= pc.damping;
    if (isnan(result)) result = phi[i];  // B3-fix NaN 防護

    phi[i] = result;
}
