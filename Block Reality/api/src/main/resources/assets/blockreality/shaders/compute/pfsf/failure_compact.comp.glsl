#version 450
#extension GL_KHR_shader_subgroup_ballot : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Failure Compact — GPU 端失敗結果壓縮
//  將 N 個 fail_flags 壓縮為 M 個非零結果（M << N）
//  避免讀回整個 fail_flags[] 陣列
//
//  舊：100K 方塊 → 讀回 100KB（全部）
//  新：100K 方塊、3 個斷裂 → 讀回 24 bytes（壓縮後）
//
//  輸出格式：
//    compactResult[0] = count（實際斷裂數）
//    compactResult[1..M] = packed (flatIndex << 4 | failType)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint N;
    uint maxResults;    // 最大回傳數量（= MAX_FAILURE_PER_TICK）
} pc;

layout(set = 0, binding = 0) readonly buffer FailFlags  { uint fail_flags[]; };
layout(set = 0, binding = 1) buffer CompactResult       { uint results[];    };
// results[0] = atomic counter (number of failures found)
// results[1..maxResults] = packed failure entries

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.N) return;

    uint flag = fail_flags[i];
    if (flag == 0u) return;

    // Atomic append to compact result buffer
    uint slot = atomicAdd(results[0], 1u);
    if (slot < pc.maxResults) {
        // Pack: high 28 bits = flat index, low 4 bits = failure type
        results[slot + 1u] = (i << 4u) | (flag & 0xFu);
    }
}
