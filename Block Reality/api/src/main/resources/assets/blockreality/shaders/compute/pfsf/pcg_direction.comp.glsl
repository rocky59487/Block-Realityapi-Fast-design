#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Direction Update
//
//  p[i] = r[i] + beta * p[i]
//
//  beta = rTr_new / rTr_old，從 reductionBuf 讀取：
//    reductionBuf[0] = rTr_old（上一步的 rTr，已由 rotate 步驟更新）
//    reductionBuf[2] = rTr_new（本步計算的新 rTr）
//
//  注意：在 computeInitialResidual 之後的第一次 PCG step 中，
//  reductionBuf[0] 已包含初始 rTr。
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint N;
} pc;

layout(set = 0, binding = 0) readonly  buffer Residual  { float r[];            };
layout(set = 0, binding = 1)           buffer Direction { float p[];            };
layout(set = 0, binding = 2) readonly  buffer Type      { uint  vtype[];        };
layout(set = 0, binding = 3) readonly  buffer Reduction { float reductionBuf[]; };

void main() {
    uint gid = gl_GlobalInvocationID.x;
    if (gid >= pc.N) return;

    // Air / Anchor: p = 0
    if (vtype[gid] != 1u) {
        p[gid] = 0.0;
        return;
    }

    // Read rTr values from reduction buffer
    float rTr_old = reductionBuf[0]; // rTr from previous iteration
    float rTr_new = reductionBuf[2]; // rTr from current iteration

    // Compute beta with safeguard against division by zero
    float beta = 0.0;
    if (rTr_old > 1e-30) {
        beta = rTr_new / rTr_old;
    }

    // Clamp beta to prevent divergence
    beta = clamp(beta, 0.0, 10.0);

    // Direction update: p = r + beta * p
    float newP = r[gid] + beta * p[gid];

    // NaN 防護
    if (isnan(newP) || isinf(newP)) newP = r[gid];

    p[gid] = newP;
}
