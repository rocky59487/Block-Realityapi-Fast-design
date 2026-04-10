#version 450

// ═══════════════════════════════════════════════════════════════
//  AMG Prolongation: e_f += P · e_c
//
//  Coarse → Fine：每個 fine node i 從其 coarse aggregate 加權插值 phi 修正量。
//
//  phi_f[i] += pWeights[i] × coarsePhi[aggregation[i]]
//
//  無 atomicAdd（每個 fine node 只讀自己的 aggregate，不存在 write conflict）。
//
//  最粗層動態資料重新分佈（N_coarse ≤ 512）：
//    → single workgroup 載入全部 coarse phi 到 shared memory，直接求解。
//
//  Workgroup: 256 threads（1D flat over N_fine）
//
//  Bindings:
//   0: CoarsePhi    float[N_coarse] (readonly)  coarse correction
//   1: Aggregation  int[N_fine]     (readonly)  fine→coarse mapping
//   2: PWeights     float[N_fine]   (readonly)  prolongation weights
//   3: FinePhi      float[N_fine]   (read-write) phi[i] += w[i] * coarsePhi[agg[i]]
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint N_fine;
    uint N_coarse;
} pc;

layout(set = 0, binding = 0) readonly buffer CoarsePhi { float coarsePhi[]; };
layout(set = 0, binding = 1) readonly buffer Agg       { int   aggregation[]; };
layout(set = 0, binding = 2) readonly buffer PW        { float pWeights[];    };
layout(set = 0, binding = 3)          buffer FinePhi   { float finePhi[];     };

// ─── Shared memory for coarse phi when N_coarse ≤ 512 ───
// Dynamic data redistribution: load all coarse nodes into shared mem
// and perform direct solve without cross-SM communication.
shared float s_coarsePhi[512];

void main() {
    uint i = gl_GlobalInvocationID.x;

    // ─── Dynamic redistribution: load coarse phi into shared memory ───
    // When N_coarse ≤ 512, all coarse nodes fit in one workgroup's shared mem.
    // Each thread loads one coarse node (if within range).
    if (gl_LocalInvocationID.x < pc.N_coarse) {
        s_coarsePhi[gl_LocalInvocationID.x] = coarsePhi[gl_LocalInvocationID.x];
    }
    barrier();

    if (i >= pc.N_fine) return;

    int agg = aggregation[i];
    if (agg < 0) return;  // unaggregated node (anchor)

    float coarseCorr;
    if (pc.N_coarse <= 512u) {
        // Fast path: read from shared memory (no global memory access)
        coarseCorr = s_coarsePhi[uint(agg)];
    } else {
        // Fallback: read from global memory (large coarse grid)
        coarseCorr = coarsePhi[uint(agg)];
    }

    // Prolongation: phi_f[i] += P[i] × e_c[agg(i)]
    finePhi[i] += pWeights[i] * coarseCorr;
}
