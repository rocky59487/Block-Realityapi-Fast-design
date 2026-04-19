#version 460 core

/**
 * LOD3 SVDAG Ray Marching Compute Shader
 *
 * 遠場 LOD3 渲染：稀疏體素 DAG (Sparse Voxel DAG) 的 DDA octree 遍歷。
 *
 * 局部工作組大小：8×8×1（總 64 執行緒/工作組）
 *
 * 輸入綁定（set=0）：
 *   [0] DAG SSBO         — header (8×uint32) + nodes (9×uint32/node)
 *   [1] Camera UBO       — invViewProj (mat4), cameraPos (vec3)
 *   [2] Output color     — rgba16f, layout(binding=2, rgba16f, set=0)
 *   [3] Output depth     — r32f,     layout(binding=3, r32f, set=0)
 *
 * DAG 節點格式（serializeForGPU）：
 *   uint flags           — childMask(8b) | matId(8b) | lodLevel(8b) | _reserved(8b)
 *   uint child[0..7]     — 8 個子節點絕對索引（0 = 空 slot）
 */

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// ──────────────────────────────────────────────────────────────────────────────
// 綁定與佈局
// ──────────────────────────────────────────────────────────────────────────────

// Binding 0: DAG SSBO
layout(binding = 0, std430) readonly buffer DAGBuffer {
    // Header (8 × uint32 = 32 bytes)
    uint dagNodeCount;
    uint dagDepth;
    uint dagOriginX;
    uint dagOriginY;
    uint dagOriginZ;
    uint dagSize;
    uint dagRootIndex;
    uint dagPad;
    // Per-node (9 × uint32 = 36 bytes stride)
    uint dagNodes[];
};

// Binding 1: Camera UBO
layout(binding = 1, std140) uniform CameraBuffer {
    mat4  invViewProj;
    vec3  cameraPos;
    float _pad1;
};

// Binding 2: Output color image (rgba16f)
layout(binding = 2, rgba16f) uniform image2D outputColor;

// Binding 3: Output depth image (r32f, linear depth in blocks)
layout(binding = 3, r32f) uniform image2D outputDepth;

// ──────────────────────────────────────────────────────────────────────────────
// 常數與工具函式
// ──────────────────────────────────────────────────────────────────────────────

const vec3 SKY_COLOR = vec3(0.5, 0.7, 1.0);
const float FOG_DENSITY = 0.0001;  // per block
const int MAX_TRAVERSE_DEPTH = 10;

/** 材質顏色查找（簡化版，由 materialId 直接決定） */
vec3 getMaterialColor(uint matId) {
    // 模擬簡單的材質調色盤
    if (matId == 0u) return vec3(0.8, 0.8, 0.8);  // stone
    if (matId == 1u) return vec3(0.2, 0.7, 0.2);  // grass
    if (matId == 2u) return vec3(0.6, 0.4, 0.2);  // dirt
    if (matId == 3u) return vec3(0.1, 0.1, 0.1);  // dark
    return vec3(0.5, 0.5, 0.5);  // default
}

/** 指數霧計算 */
vec3 applyFog(vec3 color, float distance) {
    float fogFactor = exp(-distance * FOG_DENSITY);
    return mix(SKY_COLOR, color, fogFactor);
}

// ──────────────────────────────────────────────────────────────────────────────
// DAG 遍歷（DDA Octree Traversal）
// ──────────────────────────────────────────────────────────────────────────────

/** DAG 節點讀取（索引 idx） */
void dagReadNode(uint idx, out uint flags, out uvec3 childIndices[2]) {
    uint stride = 9u;
    uint base = 8u + idx * stride;  // skip header

    flags = dagNodes[base];
    for (int i = 0; i < 2; i++) {
        childIndices[i].x = dagNodes[base + 1u + uint(i)*2u];
        childIndices[i].y = dagNodes[base + 2u + uint(i)*2u];
        childIndices[i].z = dagNodes[base + 3u + uint(i)*2u];
    }
}

/** 從 flags 提取材質 ID */
uint extractMaterialId(uint flags) {
    return (flags >> 8u) & 0xFFu;
}

/** 從 flags 提取 childMask */
uint extractChildMask(uint flags) {
    return flags & 0xFFu;
}

/**
 * 在 DAG 中查詢指定體素座標的材質 ID。
 * 使用 DDA 遍歷，返回材質 ID 或 0（空氣）。
 */
uint dagQuery(ivec3 voxelPos) {
    // 夾限座標到 DAG 範圍
    int dagX = voxelPos.x - int(dagOriginX);
    int dagY = voxelPos.y - int(dagOriginY);
    int dagZ = voxelPos.z - int(dagOriginZ);

    if (dagX < 0 || dagX >= int(dagSize) ||
        dagY < 0 || dagY >= int(dagSize) ||
        dagZ < 0 || dagZ >= int(dagSize)) {
        return 0u;
    }

    uint nodeIdx = dagRootIndex;
    uint currentSize = dagSize;

    // DDA 遍歷到葉節點或最大深度
    for (int depth = 0; depth < MAX_TRAVERSE_DEPTH; depth++) {
        if (currentSize <= 1u) break;

        uint flags;
        uvec3 childIndices[2];
        dagReadNode(nodeIdx, flags, childIndices);

        uint childMask = extractChildMask(flags);
        if (childMask == 0u) {
            // 葉節點
            return extractMaterialId(flags);
        }

        // 決定八分空間
        uint half = currentSize >> 1u;
        uint octant = 0u;
        if (uint(dagX) >= half) octant |= 1u;
        if (uint(dagY) >= half) octant |= 2u;
        if (uint(dagZ) >= half) octant |= 4u;

        // 檢查子節點是否存在
        if ((childMask & (1u << octant)) == 0u) {
            return 0u;  // 空 octant
        }

        // 取得子節點索引
        uint childNodeIdx = 0u;
        if (octant < 4u) {
            childNodeIdx = childIndices[0][octant];
        } else {
            childNodeIdx = childIndices[1][octant - 4u];
        }

        if (childNodeIdx == 0u) return 0u;

        // 更新座標與大小
        nodeIdx = childNodeIdx;
        currentSize = half;

        // 座標相對於子節點
        if (int(dagX) >= int(half)) dagX -= int(half);
        if (int(dagY) >= int(half)) dagY -= int(half);
        if (int(dagZ) >= int(half)) dagZ -= int(half);
    }

    return 0u;
}

// ──────────────────────────────────────────────────────────────────────────────
// 射線追蹤主入口
// ──────────────────────────────────────────────────────────────────────────────

void main() {
    // 計算像素座標與紋理座標
    ivec2 pixelCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imageSize = imageSize(outputColor);

    if (pixelCoord.x >= imageSize.x || pixelCoord.y >= imageSize.y) {
        return;
    }

    // 正規化紋理座標 [0, 1]
    vec2 uv = vec2(pixelCoord) / vec2(imageSize);

    // 從 NDC 座標 [-1, 1] 反投影射線
    vec4 ndcNear = vec4(uv * 2.0 - 1.0, -1.0, 1.0);
    vec4 ndcFar  = vec4(uv * 2.0 - 1.0,  1.0, 1.0);

    vec3 rayOrigin = cameraPos;
    vec4 rayDirH = invViewProj * ndcFar;
    vec3 rayDir = normalize(rayDirH.xyz / rayDirH.w - cameraPos);

    // 簡單射線步進：每步 1 block，最多 512 步
    vec3 rayPos = rayOrigin;
    vec3 color = SKY_COLOR;
    float hitDistance = -1.0;
    const int MAX_STEPS = 512;
    const float STEP_SIZE = 1.0;

    for (int step = 0; step < MAX_STEPS; step++) {
        ivec3 voxelCoord = ivec3(floor(rayPos));

        // DAG 查詢
        uint matId = dagQuery(voxelCoord);

        if (matId != 0u) {
            // 命中非空體素
            color = getMaterialColor(matId);
            hitDistance = length(rayPos - rayOrigin);
            break;
        }

        // 步進
        rayPos += rayDir * STEP_SIZE;

        // 距離上限（避免遠場無限循環）
        if (length(rayPos - rayOrigin) > 2048.0) {
            color = SKY_COLOR;
            hitDistance = 2048.0;
            break;
        }
    }

    // 應用霧效果
    if (hitDistance > 0.0) {
        color = applyFog(color, hitDistance);
    }

    // 輸出
    imageStore(outputColor, pixelCoord, vec4(color, 1.0));

    // 線性深度（block 單位）
    float linearDepth = (hitDistance >= 0.0) ? hitDistance / 2048.0 : 1.0;
    imageStore(outputDepth, pixelCoord, vec4(linearDepth));
}
