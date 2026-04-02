#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int32 : require

// ══════════════════════════════════════════════════════════════════════════════
// voxelize.comp.glsl — GPU Voxelizer（P1-A：取代 CPU GreedyMesher 輸出後的體素化步驟）
//
// 將 GreedyMesher 輸出的 AABB 列表在 GPU 上體素化，寫入 DAG 相容的 uint[] SSBO。
//
// AABB 輸入佈局（SSBO，每個 AABB 佔 7 個 float）：
//   [0..2] minX, minY, minZ（世界座標，block 單位）
//   [3..5] maxX, maxY, maxZ
//   [6]    floatBitsToUint(materialId)  — 0 = 空氣（跳過）
//
// 輸出佈局（uint SSBO，線性索引 z*gDim*gDim + y*gDim + x）：
//   每個 voxel = materialId（0 = 空）
//   大小 = gridResolution^3 個 uint
//
// 清零：呼叫端在 dispatch 前必須以 vkCmdFillBuffer 清零 outputSsbo
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

// Binding 0: AABB 資料（readonly SSBO，stride = 7 floats/AABB）
layout(set = 0, binding = 0) readonly buffer AabbBuffer {
    float data[];
} aabbBuf;

// Binding 1: 輸出體素格點（uint SSBO，呼叫端提供 outputSsboHandle）
layout(set = 0, binding = 1) buffer VoxelOutput {
    uint voxels[];   // 線性索引：z*gDim^2 + y*gDim + x
} voxelOut;

// Push Constants（32 bytes，對齊 std430）
layout(push_constant) uniform PushConstants {
    uint  aabbCount;        // AABB 數量
    uint  gridResolution;   // 每軸體素數 = resolution × 16
    float originX;          // 區段世界原點 X（block 單位）
    float originY;
    float originZ;
    float voxelSize;        // 每體素世界尺寸（= 1.0 / resolution blocks）
    uint  _pad0;
    uint  _pad1;
} pc;

void main() {
    uint aabbIdx = gl_GlobalInvocationID.x;
    if (aabbIdx >= pc.aabbCount) return;

    // 讀取 AABB
    uint  base  = aabbIdx * 7u;
    float minX  = aabbBuf.data[base + 0u];
    float minY  = aabbBuf.data[base + 1u];
    float minZ  = aabbBuf.data[base + 2u];
    float maxX  = aabbBuf.data[base + 3u];
    float maxY  = aabbBuf.data[base + 4u];
    float maxZ  = aabbBuf.data[base + 5u];
    uint  matId = floatBitsToUint(aabbBuf.data[base + 6u]);

    if (matId == 0u) return;  // 空氣 AABB，跳過

    // 世界座標 → 體素索引
    float invV  = 1.0 / pc.voxelSize;
    int   gDim  = int(pc.gridResolution);

    int gMinX = clamp(int((minX - pc.originX) * invV),              0, gDim - 1);
    int gMinY = clamp(int((minY - pc.originY) * invV),              0, gDim - 1);
    int gMinZ = clamp(int((minZ - pc.originZ) * invV),              0, gDim - 1);
    int gMaxX = clamp(int(ceil((maxX - pc.originX) * invV)),        1, gDim);
    int gMaxY = clamp(int(ceil((maxY - pc.originY) * invV)),        1, gDim);
    int gMaxZ = clamp(int(ceil((maxZ - pc.originZ) * invV)),        1, gDim);

    // 以 atomicMax 寫入所有覆蓋體素（材質 ID 較大者優先，處理 AABB 邊界重疊）
    for (int z = gMinZ; z < gMaxZ; ++z) {
        for (int y = gMinY; y < gMaxY; ++y) {
            for (int x = gMinX; x < gMaxX; ++x) {
                uint idx = uint(z * gDim * gDim + y * gDim + x);
                atomicMax(voxelOut.voxels[idx], matId);
            }
        }
    }
}
