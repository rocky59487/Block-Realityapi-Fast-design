#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// ═══════════════════════════════════════════════════════════════
//  PCG Dot Product Reduction — GPU 端向量內積
//  計算 sum(vecA[i] * vecB[i])
//
//  兩階段歸約：
//    Pass 1: N 個元素 → ceil(N/512) 個局部和
//    Pass 2: ceil(N/512) 個 → 1 個全域和
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint N;           // 輸入元素數
    uint isPass2;     // 0 = pass1 (兩向量 dot), 1 = pass2 (partial sums 歸約)
} pc;

layout(set = 0, binding = 0) readonly buffer VecA    { float vecA[];    };
layout(set = 0, binding = 1) readonly buffer VecB    { float vecB[];    };
layout(set = 0, binding = 2) buffer Partials          { float partials[]; };

shared float sdata[256 + 32];

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint gid = gl_GlobalInvocationID.x;

    float mySum = 0.0;

    if (pc.isPass2 == 0u) {
        // Pass 1: dot product — sum of vecA[i] * vecB[i]
        if (gid < pc.N) {
            mySum = vecA[gid] * vecB[gid];
        }
        uint stride = gl_NumWorkGroups.x * gl_WorkGroupSize.x;
        uint idx2 = gid + stride;
        if (idx2 < pc.N) {
            mySum += vecA[idx2] * vecB[idx2];
        }
    } else {
        // Pass 2: sum reduction of partial sums
        if (gid < pc.N) {
            mySum = vecA[gid];  // vecA = partials from pass 1
        }
        uint stride = gl_NumWorkGroups.x * gl_WorkGroupSize.x;
        uint idx2 = gid + stride;
        if (idx2 < pc.N) {
            mySum += vecA[idx2];
        }
    }

    // Subgroup reduction (register shuffles, ~1 cycle)
    mySum = subgroupAdd(mySum);

    uint sgSize = gl_SubgroupSize;
    uint sgId = gl_SubgroupInvocationID;
    if (sgId == 0u) {
        sdata[tid / sgSize] = mySum;
    }
    barrier();

    // Final reduction across subgroups
    uint numSubgroups = (gl_WorkGroupSize.x + sgSize - 1u) / sgSize;
    if (tid < numSubgroups) {
        mySum = sdata[tid];
    } else {
        mySum = 0.0;
    }
    if (tid < numSubgroups) {
        mySum = subgroupAdd(mySum);
    }

    // Thread 0 writes workgroup result
    if (tid == 0u) {
        partials[gl_WorkGroupID.x] = mySum;
    }
}
