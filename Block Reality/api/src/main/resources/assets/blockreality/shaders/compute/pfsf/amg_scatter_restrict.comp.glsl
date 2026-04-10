#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// ═══════════════════════════════════════════════════════════════
//  AMG Restriction: r_c = R · r_f  (R = P^T)
//
//  Fine → Coarse：每個 fine node i 將殘差加權累積到其 coarse aggregate。
//
//  r_c[aggregation[i]] += pWeights[i] × r_f[i]   (atomic float add)
//
//  使用 floatBitsToUint CAS loop 實作 shared memory float atomicAdd，
//  因 GLSL 無原生 float atomicAdd。
//
//  Workgroup: 256 threads（1D flat over N_fine）
//
//  Bindings:
//   0: FineResidual  float[N_fine]   (readonly)  r_f
//   1: Aggregation   int[N_fine]     (readonly)  fine→coarse mapping
//   2: PWeights      float[N_fine]   (readonly)  prolongation weights
//   3: CoarseSrc     float[N_coarse] (write)     r_c (atomicAdd target)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint N_fine;
    uint N_coarse;
} pc;

layout(set = 0, binding = 0) readonly buffer FineRes    { float fineResidual[]; };
layout(set = 0, binding = 1) readonly buffer Agg        { int   aggregation[];  };
layout(set = 0, binding = 2) readonly buffer PW         { float pWeights[];     };
layout(set = 0, binding = 3)          buffer CoarseSrc  { uint  coarseSrcBits[]; }; // uint for atomic CAS

// float atomicAdd via CAS loop (standard Vulkan 1.2 technique)
void atomicAddFloat(uint idx, float val) {
    uint old_bits = coarseSrcBits[idx];
    uint new_bits;
    do {
        float old_f = uintBitsToFloat(old_bits);
        new_bits = floatBitsToUint(old_f + val);
        uint prev = atomicCompSwap(coarseSrcBits[idx], old_bits, new_bits);
        if (prev == old_bits) break;
        old_bits = prev;
    } while (true);
}

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.N_fine) return;

    int agg = aggregation[i];
    if (agg < 0) return;  // unaggregated node (anchor or isolated)

    float contrib = pWeights[i] * fineResidual[i];
    atomicAddFloat(uint(agg), contrib);
}
