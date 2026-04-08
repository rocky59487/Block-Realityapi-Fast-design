#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Direction Update (v2: Jacobi-Preconditioned)
//
//  v2: p = z + beta * p  （z = M⁻¹r = r / diag(A)）
//
//  beta = rTz_new / rTz_old
//    reductionBuf[0] = rTz_old
//    reductionBuf[2] = rTz_new
//
//  Jacobi 預條件 z 即時計算（與 pcg_update 共用同一 diag 公式）。
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint Lx, Ly, Lz;
} pc;

layout(set = 0, binding = 0) readonly  buffer Residual  { float r[];            };
layout(set = 0, binding = 1)           buffer Direction { float p[];            };
layout(set = 0, binding = 2) readonly  buffer Type      { uint  vtype[];        };
layout(set = 0, binding = 3) readonly  buffer Reduction { float reductionBuf[]; };
layout(set = 0, binding = 4) readonly  buffer Cond      { float sigma[];        };

// ─── 計算 26 連通 Laplacian 對角線（與 pcg_update 完全一致）───
float computeDiag(uint i, uint N, int igx, int igy, int igz, bool valid[6]) {
    float diag = 0.0;

    for (int d = 0; d < 6; d++) {
        if (valid[d]) {
            float s = sigma[d * N + i];
            if (s > 0.0) diag += s;
        }
    }

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
    uint gid = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (gid >= N) return;

    if (vtype[gid] != 1u) {
        p[gid] = 0.0;
        return;
    }

    // 還原 3D 座標
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

    // beta = rTz_new / rTz_old
    float rTz_old = reductionBuf[0];
    float rTz_new = reductionBuf[2];
    float beta = 0.0;
    if (rTz_old > 1e-30) {
        beta = rTz_new / rTz_old;
    }
    beta = clamp(beta, 0.0, 10.0);

    // z = M⁻¹r = r / diag(A)
    float ri = r[gid];
    float diag = computeDiag(gid, N, igx, igy, igz, valid);
    float zi = (diag > 1e-20) ? ri / diag : ri;
    if (isnan(zi) || isinf(zi)) zi = ri;

    // p = z + beta * p
    float newP = zi + beta * p[gid];
    if (isnan(newP) || isinf(newP)) newP = zi;

    p[gid] = newP;
}
