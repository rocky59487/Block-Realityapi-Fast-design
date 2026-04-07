#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Update Kernel
//
//  兩種模式：
//    isInit == 1: 初始化模式
//      r[i] = source[i] - Ap[i]  （初始殘差，alpha 傳入 -1）
//      p[i] = r[i]               （初始搜索方向）
//      計算 rTr 局部和 → partialSums[]
//
//    isInit == 0: 正常迭代模式
//      從 reductionBuf 讀取 rTr_old 和 pAp → alpha = rTr_old / pAp
//      phi[i] += alpha * p[i]
//      r[i]   -= alpha * Ap[i]
//      計算新的 rTr 局部和 → partialSums[]
//
//  每個 workgroup 256 threads，使用 subgroup reduction 計算局部和。
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint  N;
    float alpha;      // init 模式: -1.0; 正常模式: placeholder（shader 自算）
    uint  isInit;     // 1 = init, 0 = normal
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

// Shared memory for workgroup reduction (with bank conflict padding)
shared float sdata[256 + 32];

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint gid = gl_GlobalInvocationID.x;

    float localRTr = 0.0;

    if (gid < pc.N) {
        if (pc.isInit == 1u) {
            // Init mode: r = source - Ap (alpha = -1 → r = source + (-1)*Ap = source - Ap)
            if (vtype[gid] == 1u) { // solid only
                float ri = source[gid] - ap[gid];
                // NaN 防護
                if (isnan(ri) || isinf(ri)) ri = 0.0;
                r[gid] = ri;
                p[gid] = ri;  // initial direction = residual
                localRTr = ri * ri;
            } else {
                r[gid] = 0.0;
                p[gid] = 0.0;
            }
        } else {
            // Normal mode: update phi and r
            if (vtype[gid] == 1u) { // solid only
                float alpha_val = pc.alpha;
                if (pc.isInit == 0u) {
                    float rTr_old = reductionBuf[0];
                    float pAp = reductionBuf[1];
                    // alpha = rTr / pAp (with epsilon guard)
                    if (pAp > 1e-30) {
                        alpha_val = rTr_old / pAp;
                    } else {
                        alpha_val = 0.0;
                    }
                }

                phi[gid] += alpha_val * p[gid];
                r[gid]   -= alpha_val * ap[gid];

                // Clamp phi to valid range
                phi[gid] = clamp(phi[gid], 0.0, 1e7);
                if (isnan(phi[gid])) phi[gid] = 0.0;

                float ri = r[gid];
                if (isnan(ri) || isinf(ri)) {
                    ri = 0.0;
                    r[gid] = 0.0;
                }
                localRTr = ri * ri;
            }
        }
    }

    // ─── Workgroup reduction for rTr partial sum ───
    // Step 1: subgroup reduction
    localRTr = subgroupAdd(localRTr);

    uint sgSize = gl_SubgroupSize;
    uint sgId = gl_SubgroupInvocationID;
    if (sgId == 0u) {
        sdata[tid / sgSize] = localRTr;
    }
    barrier();

    // Step 2: final reduction across subgroups
    uint numSubgroups = (gl_WorkGroupSize.x + sgSize - 1u) / sgSize;
    if (tid < numSubgroups) {
        localRTr = sdata[tid];
    } else {
        localRTr = 0.0;
    }
    if (tid < numSubgroups) {
        localRTr = subgroupAdd(localRTr);
    }

    // Thread 0 writes workgroup partial sum
    if (tid == 0u) {
        partialSums[gl_WorkGroupID.x] = localRTr;
    }
}
