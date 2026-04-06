#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Red-Black Gauss-Seidel + Chebyshev — In-Place 版本
//
//  v2 升級：取代 Jacobi，收斂速度 2×，消除 phi_prev 雙重緩衝。
//  3D 棋盤著色：紅色 = (x+y+z)%2==0，黑色 = (x+y+z)%2==1
//  紅 pass 只更新紅色體素（讀黑色鄰居，保證不衝突）
//  黑 pass 只更新黑色體素（讀剛更新的紅色鄰居）
//
//  Workgroup: 8×8×4 = 256 threads
//  Shared memory tile: (8+2)×(8+2)×(4+2) = 600 floats
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;
    float omega;
    float rho_spec;
    uint  iter;
    float damping;
    uint  redBlackPass; // 0 = red, 1 = black
} pc;

// In-place: 只需一個 phi buffer（取消 PhiPrev binding）
layout(set = 0, binding = 0) buffer Phi       { float phi[];   };
layout(set = 0, binding = 1) readonly buffer Source { float rho[];   };
layout(set = 0, binding = 2) readonly buffer Cond   { float sigma[]; };
layout(set = 0, binding = 3) readonly buffer Type   { uint  vtype[]; };

#define TX 8
#define TY 8
#define TZ 4
#define SX (TX + 2)
#define SY (TY + 2)
#define SZ (TZ + 2)

shared float sPhi[SZ][SY][SX];

// v2 Phase B: Morton Z-Order indexing (inlined from morton_utils.glsl)
uint mortonExpandBits(uint v) {
    v &= 0x3FFu;
    v = (v | (v << 16u)) & 0x030000FFu;
    v = (v | (v <<  8u)) & 0x0300F00Fu;
    v = (v | (v <<  4u)) & 0x030C30C3u;
    v = (v | (v <<  2u)) & 0x09249249u;
    return v;
}

uint gIdx(uint x, uint y, uint z) {
    return mortonExpandBits(x) | (mortonExpandBits(y) << 1u) | (mortonExpandBits(z) << 2u);
}

float safeLoadPhi(int gx, int gy, int gz) {
    if (gx < 0 || uint(gx) >= pc.Lx ||
        gy < 0 || uint(gy) >= pc.Ly ||
        gz < 0 || uint(gz) >= pc.Lz) return 0.0;
    return phi[gIdx(uint(gx), uint(gy), uint(gz))];
}

void main() {
    uint gx = gl_GlobalInvocationID.x;
    uint gy = gl_GlobalInvocationID.y;
    uint gz = gl_GlobalInvocationID.z;

    uint lx = gl_LocalInvocationID.x;
    uint ly = gl_LocalInvocationID.y;
    uint lz = gl_LocalInvocationID.z;

    uint sx = lx + 1u;
    uint sy = ly + 1u;
    uint sz = lz + 1u;

    // ─── Phase 1: 協作載入 tile 到 shared memory ───
    int igx = int(gx), igy = int(gy), igz = int(gz);
    sPhi[sz][sy][sx] = safeLoadPhi(igx, igy, igz);

    // Halo
    if (lx == 0u)        sPhi[sz][sy][0u]      = safeLoadPhi(igx - 1, igy, igz);
    if (lx == TX - 1u)   sPhi[sz][sy][SX - 1u] = safeLoadPhi(igx + 1, igy, igz);
    if (ly == 0u)        sPhi[sz][0u][sx]       = safeLoadPhi(igx, igy - 1, igz);
    if (ly == TY - 1u)   sPhi[sz][SY - 1u][sx]  = safeLoadPhi(igx, igy + 1, igz);
    if (lz == 0u)        sPhi[0u][sy][sx]       = safeLoadPhi(igx, igy, igz - 1);
    if (lz == TZ - 1u)   sPhi[SZ - 1u][sy][sx]  = safeLoadPhi(igx, igy, igz + 1);

    barrier();

    // ─── Phase 2: 超界 + 紅黑著色檢查 ───
    if (gx >= pc.Lx || gy >= pc.Ly || gz >= pc.Lz) return;

    // 紅黑著色：只處理當前 pass 對應顏色的體素
    uint color = (gx + gy + gz) % 2u;
    if (color != pc.redBlackPass) return;

    uint i = gIdx(gx, gy, gz);

    if (vtype[i] == 2u) { phi[i] = 0.0; return; } // Anchor
    if (vtype[i] == 0u) { phi[i] = 0.0; return; } // Air

    // ─── Phase 3: GS 更新（從 shared memory 讀對面顏色鄰居）───
    bool valid[6] = bool[6](
        gx > 0u, gx + 1u < pc.Lx,
        gy > 0u, gy + 1u < pc.Ly,
        gz > 0u, gz + 1u < pc.Lz
    );

    float neighborPhi[6] = float[6](
        sPhi[sz][sy][sx - 1u],
        sPhi[sz][sy][sx + 1u],
        sPhi[sz][sy - 1u][sx],
        sPhi[sz][sy + 1u][sx],
        sPhi[sz - 1u][sy][sx],
        sPhi[sz + 1u][sy][sx]
    );

    float sumSigma = 0.0;
    float sumNeighbor = 0.0;

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;
        uint N = pc.Lx * pc.Ly * pc.Lz;
        float s = sigma[d * N + i];
        if (s > 0.0) {
            float np = neighborPhi[d];
            if (!isnan(np) && !isinf(np)) {
                sumSigma += s;
                sumNeighbor += s * np;
            }
        }
    }

    // ─── v2: 隱式 26-connectivity 剪力 ───
    {
        uint N = pc.Lx * pc.Ly * pc.Lz;
        float sx_neg = sigma[0 * N + i]; float sx_pos = sigma[1 * N + i];
        float sy_neg = sigma[2 * N + i]; float sy_pos = sigma[3 * N + i];
        float sz_neg = sigma[4 * N + i]; float sz_pos = sigma[5 * N + i];
        float sx_avg = (sx_neg + sx_pos) * 0.5;
        float sy_avg = (sy_neg + sy_pos) * 0.5;
        float sz_avg = (sz_neg + sz_pos) * 0.5;
        const float EDGE_P  = 0.35; // MUST match PFSFConstants.SHEAR_EDGE_PENALTY
        const float CORNER_P = 0.15; // MUST match PFSFConstants.SHEAR_CORNER_PENALTY

        // 12 edge neighbors (XY plane)
        float edgeSigma = sqrt(max(sx_avg * sy_avg, 0.0)) * EDGE_P;
        if (edgeSigma > 0.0) {
            if (valid[0] && valid[2]) { float ep = safeLoadPhi(igx-1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigma; sumNeighbor+=edgeSigma*ep; } }
            if (valid[1] && valid[3]) { float ep = safeLoadPhi(igx+1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigma; sumNeighbor+=edgeSigma*ep; } }
            if (valid[0] && valid[3]) { float ep = safeLoadPhi(igx-1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigma; sumNeighbor+=edgeSigma*ep; } }
            if (valid[1] && valid[2]) { float ep = safeLoadPhi(igx+1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigma; sumNeighbor+=edgeSigma*ep; } }
        }
        // XZ plane
        float edgeSigmaXZ = sqrt(max(sx_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaXZ > 0.0) {
            if (valid[0] && valid[4]) { float ep = safeLoadPhi(igx-1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[1] && valid[5]) { float ep = safeLoadPhi(igx+1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[0] && valid[5]) { float ep = safeLoadPhi(igx-1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
            if (valid[1] && valid[4]) { float ep = safeLoadPhi(igx+1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaXZ; sumNeighbor+=edgeSigmaXZ*ep; } }
        }
        // YZ plane
        float edgeSigmaYZ = sqrt(max(sy_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaYZ > 0.0) {
            if (valid[2] && valid[4]) { float ep = safeLoadPhi(igx,igy-1,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[3] && valid[5]) { float ep = safeLoadPhi(igx,igy+1,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[2] && valid[5]) { float ep = safeLoadPhi(igx,igy-1,igz+1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
            if (valid[3] && valid[4]) { float ep = safeLoadPhi(igx,igy+1,igz-1); if (!isnan(ep)&&!isinf(ep)) { sumSigma+=edgeSigmaYZ; sumNeighbor+=edgeSigmaYZ*ep; } }
        }
        // 8 corners
        float cornerSigma = pow(max(sx_avg * sy_avg * sz_avg, 0.0), 1.0/3.0) * CORNER_P;
        if (cornerSigma > 0.0) {
            int dx[2] = int[2](-1, 1);
            int dy[2] = int[2](-1, 1);
            int dz[2] = int[2](-1, 1);
            for (int ci = 0; ci < 8; ci++) {
                int cx = dx[ci & 1], cy = dy[(ci >> 1) & 1], cz = dz[(ci >> 2) & 1];
                int nx = igx + cx, ny = igy + cy, nz = igz + cz;
                if (nx >= 0 && nx < int(pc.Lx) && ny >= 0 && ny < int(pc.Ly) && nz >= 0 && nz < int(pc.Lz)) {
                    float cp = safeLoadPhi(nx, ny, nz);
                    if (!isnan(cp) && !isinf(cp)) { sumSigma += cornerSigma; sumNeighbor += cornerSigma * cp; }
                }
            }
        }
    }

    // ─── GS result ───
    float phi_gs;
    if (sumSigma > 0.0) {
        phi_gs = (rho[i] + sumNeighbor) / sumSigma;
    } else {
        phi_gs = 1e7;
    }

    // Chebyshev extrapolation (SOR-compatible)
    float prev = sPhi[sz][sy][sx]; // loaded before in-place write
    float result = pc.omega * (phi_gs - prev) + prev;

    if (pc.damping > 0.0) result *= pc.damping;
    result = clamp(result, 0.0, 1e7);
    if (isnan(result)) result = prev;

    phi[i] = result; // in-place write
}
