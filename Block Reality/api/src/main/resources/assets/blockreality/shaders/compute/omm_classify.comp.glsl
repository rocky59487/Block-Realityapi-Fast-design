#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int32 : require

// ══════════════════════════════════════════════════════════════════════════════
// omm_classify.comp.glsl — GPU-side Opacity Micromap 狀態分類（P2-B）
//
// 將 section blockTypes 陣列（16³ = 4096 個材料 ID）與三角形對方塊映射表
// 轉換為 4-state OMM bit array，供 VkMicromapEXT 建構使用。
//
// 輸出格式：VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT（2 bits/micro-triangle）
//   00 = TRANSPARENT（ray 穿透，不觸發 any-hit）
//   01 = OPAQUE（ray 命中，跳過 any-hit）
//   10 = UNKNOWN_TRANSPARENT（觸發 any-hit 決定）
//   11 = UNKNOWN_OPAQUE（觸發 any-hit 決定）
//
// 本 shader 使用 UNKNOWN_TRANSPARENT(10) 作為 alpha-tested 材料，
// 確保 any-hit shader 在需要時被呼叫以正確計算 alpha 值。
//
// Binding 佈局（set 0）：
//   0  uint[] SSBO  blockTypes（4096 bytes，每個元素為材料 ID 0-255）
//   1  uint[] SSBO  triToBlock（triangleCount 個 uint，每個為 blockTypes 的索引）
//   2  uint[] SSBO  transparencyFlags（256 個 uint：0=opaque, 1=alpha-tested, 2=transparent）
//   3  uint[] SSBO  ommOutput（輸出 4-state OMM bit array，packed 2 bits/micro-triangle）
//
// Push Constants（16 bytes）：
//   triangleCount, subdivisionLevel（固定 2 → 4 micro-triangles/triangle）
//   ommBytesPerTriangle（= subdivLevel²/4 * 2 bits，補齊到 bytes）
//   totalOmmDwords（輸出 buffer 的 uint 數量）
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

// Binding 0：blockTypes（4096 bytes，一個 section 的材料 ID，packed 4 per uint）
layout(set = 0, binding = 0) readonly buffer BlockTypes {
    uint data[];   // packed: 4 bytes per uint, index as data[i/4] >> ((i%4)*8) & 0xFF
} blockTypesBuf;

// Binding 1：三角形 → 方塊索引映射（每個三角形對應的 blockTypes 陣列索引）
layout(set = 0, binding = 1) readonly buffer TriToBlock {
    uint indices[];
} triToBlock;

// Binding 2：每種材料 ID 的透明度類型（256 個 uint）
//   0 = OPAQUE（石頭、磚塊等）→ OMM 狀態 01（OPAQUE）
//   1 = ALPHA_TESTED（玻璃、葉片、草等）→ OMM 狀態 10（UNKNOWN_TRANSPARENT）
//   2 = TRANSLUCENT（水等 alpha-blend）→ OMM 狀態 10（UNKNOWN_TRANSPARENT）
//   3 = AIR / EMPTY → OMM 狀態 00（TRANSPARENT）
layout(set = 0, binding = 2) readonly buffer TransparencyFlags {
    uint flags[];
} transFlags;

// Binding 3：OMM 輸出（packed 2 bits/micro-triangle，每個 uint 儲存 16 個 micro-triangle 狀態）
layout(set = 0, binding = 3) buffer OMMOutput {
    uint data[];
} ommOut;

layout(push_constant) uniform PC {
    uint triangleCount;
    uint subdivisionLevel;   // 固定 2（4 micro-triangles/triangle）
    uint microTriPerTri;     // = 4^subdivisionLevel = 4（subdivision level 2）
    uint totalOmmDwords;     // = ceil(triangleCount * microTriPerTri * 2bits / 32bits)
} pc;

// 4-state OMM 常數（VkOpacityMicromapStateEXT）
const uint OMM_TRANSPARENT         = 0u;  // 00 — ray 穿透
const uint OMM_OPAQUE              = 1u;  // 01 — ray 命中，跳過 any-hit
const uint OMM_UNKNOWN_TRANSPARENT = 2u;  // 10 — 觸發 any-hit（從透明側）
const uint OMM_UNKNOWN_OPAQUE      = 3u;  // 11 — 觸發 any-hit（從不透明側）

// 從 blockTypes packed SSBO 讀取單個材料 ID
uint getBlockType(uint blockIdx) {
    uint dword = blockTypesBuf.data[blockIdx >> 2u];
    uint shift = (blockIdx & 3u) << 3u;
    return (dword >> shift) & 0xFFu;
}

// 根據透明度類型取得 4-state OMM 值
uint transparencyToOMM(uint transType) {
    if (transType == 3u) return OMM_TRANSPARENT;          // 空氣/空方塊 → 穿透
    if (transType == 0u) return OMM_OPAQUE;               // 完全不透明 → 跳過 any-hit
    return OMM_UNKNOWN_TRANSPARENT;                        // alpha-tested/translucent → any-hit
}

void main() {
    uint triIdx = gl_GlobalInvocationID.x;
    if (triIdx >= pc.triangleCount) return;

    // 取得此三角形對應的方塊材料
    uint blockIdx  = triToBlock.indices[triIdx];
    uint matId     = getBlockType(blockIdx);
    uint transType = transFlags.flags[matId & 0xFFu];
    uint ommState  = transparencyToOMM(transType);

    // subdivision level 2 → 4 micro-triangles/triangle，每個都設為相同狀態
    // （細緻的 per-micro-triangle 分類需要更多幾何資訊，此處保守使用均一值）
    //
    // 輸出 packed bit array：2 bits/micro-triangle，16 micro-triangles/dword
    // triIdx 的 4 個 micro-triangle 從 bitOffset = triIdx * 8 開始（= triIdx * 4 * 2 bits）

    uint bitOffset = triIdx * pc.microTriPerTri * 2u;  // bits from start of output buffer
    uint dwordIdx  = bitOffset >> 5u;                   // which dword
    uint bitInDword = bitOffset & 31u;                  // bit position within dword

    // 4 個 micro-triangle 的 2-bit 狀態，packed 成 8 bits
    uint packed = (ommState)
                | (ommState << 2u)
                | (ommState << 4u)
                | (ommState << 6u);

    // atomic OR 寫入（多個 thread 可能寫入相鄰 dword 的不同 bit 區間）
    if (dwordIdx < pc.totalOmmDwords) {
        atomicOr(ommOut.data[dwordIdx], packed << bitInDword);
        // 處理跨 dword 邊界（當 bitInDword > 24，part of the 8-bit packed spills over）
        if (bitInDword > 24u && dwordIdx + 1u < pc.totalOmmDwords) {
            atomicOr(ommOut.data[dwordIdx + 1u], packed >> (32u - bitInDword));
        }
    }
}
