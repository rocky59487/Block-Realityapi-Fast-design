#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Update Kernel (v2: Jacobi-Preconditioned)
//
//  v2 升級：加入 Jacobi 預條件 z = M⁻¹r = r / diag(A)
//
//  兩種模式：
//    isInit == 1: 初始化模式
//      r[i] = source[i] - Ap[i]
//      z[i] = r[i] / diag[i]    （Jacobi 預條件）
//      p[i] = z[i]               （初始搜索方向 = 預條件殘差）
//      計算 r·z 局部和 → partialSums[]
//
//    isInit == 0: 正常迭代模式
//      alpha = rTz_old / pAp
//      phi[i] += alpha * p[i]
//      r[i]   -= alpha * Ap[i]
//      z[i]   = r[i] / diag[i]  （Jacobi 預條件）
//      計算新的 r·z 局部和 → partialSums[]
//
//  Jacobi 預條件 M = diag(A)，其中 A 為 26 連通 Laplacian。
//  diag_i = Σ (face σ) + Σ (edge σ) + Σ (corner σ) 與 rbgs_smooth 的 sumSigma 一致。
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;  // v2: 從 N 改為 3D 維度（需計算 diag）
    float alpha;
    uint  isInit;
    uint  padding;
} pc;

layout(set = 0, binding = 0) buffer Phi         { float phi[];         };
layout(set = 0, binding = 1) buffer Residual    { float r[];           };
layout(set = 0, binding = 2) buffer Direction   { float p[];           };
layout(set = 0, binding = 3) readonly buffer Ap { float ap[];          };
layout(set = 0, binding = 4) readonly buffer Source { float source[];  };
layout(set = 0, binding = 5) readonly buffer Type   { uint  vtype[];   };
layout(set = 0, binding = 6) buffer PartialSums { float partialSums[]; };
layout(set = 0, binding = 7) readonly buffer Reduction { float reductionBuf[]; };
layout(set = 0, binding = 8) readonly buffer Cond { float sigma[]; };

shared float sdata[256 + 32];

// ─── 計算 26 連通 Laplacian 對角線（與 rbgs_smooth 的 sumSigma 一致）───
float computeDiag(uint i, uint N, int igx, int igy, int igz, bool valid[6]) {
    float diag = 0.0;

    // 6 face neighbors
    for (int d = 0; d < 6; d++) {
        if (valid[d]) {
            float s = sigma[d * N + i];
            if (s > 0.0) diag += s;
        }
    }

    // Edge + corner (same formula as rbgs_smooth)
    float sx_neg = sigma[0 * N + i]; float sx_pos = sigma[1 * N + i];
    float sy_neg = sigma[2 * N + i]; float sy_pos = sigma[3 * N + i];
    float sz_neg = sigma[4 * N + i]; float sz_pos = sigma[5 * N + i];
    float sx_avg = (sx_neg + sx_pos) * 0.5;
    float sy_avg = (sy_neg + sy_pos) * 0.5;
    float sz_avg = (sz_neg + sz_pos) * 0.5;
    const float EDGE_P   = 0.35;
    const float CORNER_P = 0.15;

    float edgeSigmaXY = sqrt(max(sx_avg * sy_avg, 0.0)) * EDGE_P;
    if (edgeSigmaXY > 0.0) {
        if (valid[0] && valid[2]) diag += edgeSigmaXY;
        if (valid[1] && valid[3]) diag += edgeSigmaXY;
        if (valid[0] && valid[3]) diag += edgeSigmaXY;
        if (valid[1] && valid[2]) diag += edgeSigmaXY;
    }
    float edgeSigmaXZ = sqrt(max(sx_avg * sz_avg, 0.0)) * EDGE_P;
    if (edgeSigmaXZ > 0.0) {
        if (valid[0] && valid[4]) diag += edgeSigmaXZ;
        if (valid[1] && valid[5]) diag += edgeSigmaXZ;
        if (valid[0] && valid[5]) diag += edgeSigmaXZ;
        if (valid[1] && valid[4]) diag += edgeSigmaXZ;
    }
    float edgeSigmaYZ = sqrt(max(sy_avg * sz_avg, 0.0)) * EDGE_P;
    if (edgeSigmaYZ > 0.0) {
        if (valid[2] && valid[4]) diag += edgeSigmaYZ;
        if (valid[3] && valid[5]) diag += edgeSigmaYZ;
        if (valid[2] && valid[5]) diag += edgeSigmaYZ;
        if (valid[3] && valid[4]) diag += edgeSigmaYZ;
    }

    float cornerSigma = pow(max(sx_avg * sy_avg * sz_avg, 0.0), 1.0/3.0) * CORNER_P;
    if (cornerSigma > 0.0) {
        int dxc[2] = int[2](-1, 1);
        int dyc[2] = int[2](-1, 1);
        int dzc[2] = int[2](-1, 1);
        for (int ci = 0; ci < 8; ci++) {
            int cx = dxc[ci & 1], cy = dyc[(ci >> 1) & 1], cz = dzc[(ci >> 2) & 1];
            int nx = igx + cx, ny = igy + cy, nz = igz + cz;
            if (nx >= 0 && nx < int(pc.Lx) && ny >= 0 && ny < int(pc.Ly) && nz >= 0 && nz < int(pc.Lz)) {
                diag += cornerSigma;
            }
        }
    }

    return diag;
}

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint gid = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;

    float localRTz = 0.0;

    if (gid < N) {
        // 還原 3D 座標（需要計算 diag）
        uint gx = gid % pc.Lx;
        uint rem = gid / pc.Lx;
        uint gy = rem % pc.Ly;
        uint gz = rem / pc.Ly;
        int igx = int(gx), igy = int(gy), igz = int(gz);

        bool valid[6] = bool[6](
            gx > 0u, gx + 1u < pc.Lx,
            gy > 0u, gy + 1u < pc.Ly,
            gz > 0u, gz + 1u < pc.Lz
        );

        if (pc.isInit == 1u) {
            if (vtype[gid] == 1u) {
                float ri = source[gid] - ap[gid];
                if (isnan(ri) || isinf(ri)) ri = 0.0;
                r[gid] = ri;

                // Jacobi 預條件: z = r / diag(A)
                float diag = computeDiag(gid, N, igx, igy, igz, valid);
                float zi = (diag > 1e-20) ? ri / diag : ri;
                if (isnan(zi) || isinf(zi)) zi = 0.0;

                p[gid] = zi;  // p = z（預條件殘差）
                localRTz = ri * zi;  // r·z（PCG 內積）
            } else {
                r[gid] = 0.0;
                p[gid] = 0.0;
            }
        } else {
            if (vtype[gid] == 1u) {
                float alpha_val = pc.alpha;
                if (pc.isInit == 0u) {
                    float rTz_old = reductionBuf[0];
                    float pAp = reductionBuf[1];
                    if (pAp > 1e-30) {
                        alpha_val = rTz_old / pAp;
                    } else {
                        alpha_val = 0.0;
                    }
                }

                phi[gid] += alpha_val * p[gid];
                r[gid]   -= alpha_val * ap[gid];

                phi[gid] = clamp(phi[gid], 0.0, 1e7);
                if (isnan(phi[gid])) phi[gid] = 0.0;

                float ri = r[gid];
                if (isnan(ri) || isinf(ri)) {
                    ri = 0.0;
                    r[gid] = 0.0;
                }

                // Jacobi 預條件: z = r / diag(A)
                float diag = computeDiag(gid, N, igx, igy, igz, valid);
                float zi = (diag > 1e-20) ? ri / diag : ri;
                if (isnan(zi) || isinf(zi)) zi = 0.0;

                localRTz = ri * zi;  // r·z（PCG 內積）
            }
        }
    }

    // ─── Workgroup reduction for r·z partial sum ───
    localRTz = subgroupAdd(localRTz);

    uint sgSize = gl_SubgroupSize;
    uint sgId = gl_SubgroupInvocationID;
    if (sgId == 0u) {
        sdata[tid / sgSize] = localRTz;
    }
    barrier();

    uint numSubgroups = (gl_WorkGroupSize.x + sgSize - 1u) / sgSize;
    if (tid < numSubgroups) {
        localRTz = sdata[tid];
    } else {
        localRTz = 0.0;
    }
    if (tid < numSubgroups) {
        localRTz = subgroupAdd(localRTz);
    }

    if (tid == 0u) {
        partialSums[gl_WorkGroupID.x] = localRTz;
    }
}
