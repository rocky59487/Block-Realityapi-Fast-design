#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Matrix-Vector Product (26-Connectivity Laplacian)
//
//  v2: 升級為 26 連通 stencil，與 rbgs_smooth.comp.glsl 完全一致。
//
//  數學要求：CG 收斂定理要求 matvec 算子 A 與 smoother 的算子相同。
//  舊版只用 6 面鄰居（L₆），但 RBGS 用 6+12+8 = 26 鄰居（L₂₆）。
//  這導致 PCG 求解的方程式與 RBGS 不同 → 收斂到錯誤的解。
//
//  Ap[i] = Σⱼ σᵢⱼ(φᵢ - φⱼ)  for all 26 neighbors j
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint Lx, Ly, Lz;
} pc;

layout(set = 0, binding = 0) readonly  buffer InputVec  { float inputVec[];  };
layout(set = 0, binding = 1) writeonly buffer OutputVec { float outputVec[]; };
layout(set = 0, binding = 2) readonly  buffer Cond      { float sigma[];     };
layout(set = 0, binding = 3) readonly  buffer Type      { uint  vtype[];     };

uint gIdx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

float safeInput(int gx, int gy, int gz) {
    if (gx < 0 || uint(gx) >= pc.Lx ||
        gy < 0 || uint(gy) >= pc.Ly ||
        gz < 0 || uint(gz) >= pc.Lz) return 0.0;
    return inputVec[gIdx(uint(gx), uint(gy), uint(gz))];
}

void main() {
    uint flatIdx = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (flatIdx >= N) return;

    uint gx = flatIdx % pc.Lx;
    uint rem = flatIdx / pc.Lx;
    uint gy = rem % pc.Ly;
    uint gz = rem / pc.Ly;
    uint i = flatIdx;

    // Air / Anchor: Ap = 0
    if (vtype[i] == 0u || vtype[i] == 2u) {
        outputVec[i] = 0.0;
        return;
    }

    bool valid[6] = bool[6](
        gx > 0u, gx + 1u < pc.Lx,
        gy > 0u, gy + 1u < pc.Ly,
        gz > 0u, gz + 1u < pc.Lz
    );

    int igx = int(gx), igy = int(gy), igz = int(gz);
    float centerVal = inputVec[i];
    float result = 0.0;

    // ─── 6 面鄰居（與 rbgs_smooth 完全一致）───
    float neighborVal[6] = float[6](
        safeInput(igx - 1, igy, igz),
        safeInput(igx + 1, igy, igz),
        safeInput(igx, igy - 1, igz),
        safeInput(igx, igy + 1, igz),
        safeInput(igx, igy, igz - 1),
        safeInput(igx, igy, igz + 1)
    );

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;
        float s = sigma[d * N + i];
        if (s > 0.0) {
            float nv = neighborVal[d];
            if (!isnan(nv) && !isinf(nv)) {
                result += s * (centerVal - nv);
            }
        }
    }

    // ─── 12 邊鄰居 + 8 角鄰居（v2: 與 rbgs_smooth 的 26 連通完全一致）───
    {
        float sx_neg = sigma[0 * N + i]; float sx_pos = sigma[1 * N + i];
        float sy_neg = sigma[2 * N + i]; float sy_pos = sigma[3 * N + i];
        float sz_neg = sigma[4 * N + i]; float sz_pos = sigma[5 * N + i];
        float sx_avg = (sx_neg + sx_pos) * 0.5;
        float sy_avg = (sy_neg + sy_pos) * 0.5;
        float sz_avg = (sz_neg + sz_pos) * 0.5;
        const float EDGE_P   = 0.35;   // MUST match PFSFConstants.SHEAR_EDGE_PENALTY
        const float CORNER_P = 0.15;   // MUST match PFSFConstants.SHEAR_CORNER_PENALTY

        // 12 edge neighbors (XY plane)
        float edgeSigmaXY = sqrt(max(sx_avg * sy_avg, 0.0)) * EDGE_P;
        if (edgeSigmaXY > 0.0) {
            if (valid[0] && valid[2]) { float ep = safeInput(igx-1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXY * (centerVal - ep); }
            if (valid[1] && valid[3]) { float ep = safeInput(igx+1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXY * (centerVal - ep); }
            if (valid[0] && valid[3]) { float ep = safeInput(igx-1,igy+1,igz); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXY * (centerVal - ep); }
            if (valid[1] && valid[2]) { float ep = safeInput(igx+1,igy-1,igz); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXY * (centerVal - ep); }
        }
        // 12 edge neighbors (XZ plane)
        float edgeSigmaXZ = sqrt(max(sx_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaXZ > 0.0) {
            if (valid[0] && valid[4]) { float ep = safeInput(igx-1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXZ * (centerVal - ep); }
            if (valid[1] && valid[5]) { float ep = safeInput(igx+1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXZ * (centerVal - ep); }
            if (valid[0] && valid[5]) { float ep = safeInput(igx-1,igy,igz+1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXZ * (centerVal - ep); }
            if (valid[1] && valid[4]) { float ep = safeInput(igx+1,igy,igz-1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaXZ * (centerVal - ep); }
        }
        // 12 edge neighbors (YZ plane)
        float edgeSigmaYZ = sqrt(max(sy_avg * sz_avg, 0.0)) * EDGE_P;
        if (edgeSigmaYZ > 0.0) {
            if (valid[2] && valid[4]) { float ep = safeInput(igx,igy-1,igz-1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaYZ * (centerVal - ep); }
            if (valid[3] && valid[5]) { float ep = safeInput(igx,igy+1,igz+1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaYZ * (centerVal - ep); }
            if (valid[2] && valid[5]) { float ep = safeInput(igx,igy-1,igz+1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaYZ * (centerVal - ep); }
            if (valid[3] && valid[4]) { float ep = safeInput(igx,igy+1,igz-1); if (!isnan(ep)&&!isinf(ep)) result += edgeSigmaYZ * (centerVal - ep); }
        }
        // 8 corner neighbors
        float cornerSigma = pow(max(sx_avg * sy_avg * sz_avg, 0.0), 1.0/3.0) * CORNER_P;
        if (cornerSigma > 0.0) {
            int dxc[2] = int[2](-1, 1);
            int dyc[2] = int[2](-1, 1);
            int dzc[2] = int[2](-1, 1);
            for (int ci = 0; ci < 8; ci++) {
                int cx = dxc[ci & 1], cy = dyc[(ci >> 1) & 1], cz = dzc[(ci >> 2) & 1];
                int nx = igx + cx, ny = igy + cy, nz = igz + cz;
                if (nx >= 0 && nx < int(pc.Lx) && ny >= 0 && ny < int(pc.Ly) && nz >= 0 && nz < int(pc.Lz)) {
                    float cp = safeInput(nx, ny, nz);
                    if (!isnan(cp) && !isinf(cp)) {
                        result += cornerSigma * (centerVal - cp);
                    }
                }
            }
        }
    }

    if (isnan(result) || isinf(result)) result = 0.0;
    outputVec[i] = result;
}
