#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Phi Max Reduction — GPU 端平行求最大值
//  將 N 個 phi 值歸約為 1 個最大值
//  避免讀回整個 phi[] 陣列（4MB → 4 bytes）
//
//  兩階段歸約：
//    Pass 1: N 個元素 → ceil(N/512) 個局部最大值
//    Pass 2: ceil(N/512) 個 → 1 個全域最大值
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint N;           // 輸入元素數
    uint isPass2;     // 0 = pass1 (phi→partial), 1 = pass2 (partial→final)
} pc;

layout(set = 0, binding = 0) readonly buffer Input  { float inputArr[];  };
layout(set = 0, binding = 1) buffer Output          { float outputArr[]; };

shared float sdata[256];

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint gid = gl_GlobalInvocationID.x;

    // Load: each thread handles 2 elements (grid-stride)
    float myMax = -1e30;
    if (gid < pc.N) {
        myMax = inputArr[gid];
    }
    uint stride = gl_NumWorkGroups.x * gl_WorkGroupSize.x;
    uint idx2 = gid + stride;
    if (idx2 < pc.N) {
        myMax = max(myMax, inputArr[idx2]);
    }

    sdata[tid] = myMax;
    barrier();

    // Shared memory reduction (unrolled for performance)
    if (tid < 128u) sdata[tid] = max(sdata[tid], sdata[tid + 128u]);
    barrier();
    if (tid < 64u) sdata[tid] = max(sdata[tid], sdata[tid + 64u]);
    barrier();

    // Warp-level reduction (no barrier needed for tid < 32)
    if (tid < 32u) {
        sdata[tid] = max(sdata[tid], sdata[tid + 32u]);
        sdata[tid] = max(sdata[tid], sdata[tid + 16u]);
        sdata[tid] = max(sdata[tid], sdata[tid + 8u]);
        sdata[tid] = max(sdata[tid], sdata[tid + 4u]);
        sdata[tid] = max(sdata[tid], sdata[tid + 2u]);
        sdata[tid] = max(sdata[tid], sdata[tid + 1u]);
    }

    // Thread 0 writes workgroup result
    if (tid == 0u) {
        outputArr[gl_WorkGroupID.x] = sdata[0];
    }
}
