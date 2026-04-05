#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Phi Max Reduction — GPU 端平行求最大值 (v2: Subgroup ops)
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

// B9-fix: padding +32 to avoid 4-way bank conflicts on 32-bank shared memory
shared float sdata[256 + 32];

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

    // ★ v2: Subgroup reduction first (3-5× faster, eliminates shared memory barriers)
    // subgroupMax reduces within a warp/wavefront using register shuffles (~1 cycle)
    myMax = subgroupMax(myMax);

    // Only subgroup leader writes to shared memory (reduces bank conflicts by subgroupSize×)
    uint sgSize = gl_SubgroupSize;
    uint sgId = gl_SubgroupInvocationID;
    if (sgId == 0u) {
        sdata[tid / sgSize] = myMax;
    }
    barrier();

    // Final reduction across subgroups in shared memory
    uint numSubgroups = (gl_WorkGroupSize.x + sgSize - 1u) / sgSize;
    if (tid < numSubgroups) {
        myMax = sdata[tid];
    } else {
        myMax = -1e30;
    }
    if (tid < numSubgroups) {
        myMax = subgroupMax(myMax);
    }

    // Thread 0 writes workgroup result
    if (tid == 0u) {
        outputArr[gl_WorkGroupID.x] = myMax;
    }
}
