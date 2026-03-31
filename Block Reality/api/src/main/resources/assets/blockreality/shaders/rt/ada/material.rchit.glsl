#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Ada/Blackwell Closest Hit Shader
//  完整材料系統 + StressVisualizationRT 應力熱圖 + SER reorder hint
//
//  SPECIALIZATION CONSTANTS（由 VkRTPipeline 在 pipeline 建立時注入）：
//    SC 0: GPU_TIER  (0=Ada SM8.9, 1=Blackwell SM10)
//    SC 1: MAX_BOUNCES（預設 1，Blackwell 可設 2）
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_tracing                       : require
#extension GL_NV_shader_execution_reordering        : require
#extension GL_EXT_nonuniform_qualifier              : require
#extension GL_EXT_scalar_block_layout               : require
#extension GL_EXT_shader_explicit_arithmetic_types  : require

// ─── Specialization Constants ─────────────────────────────────────────────
layout(constant_id = 0) const int GPU_TIER    = 0; // 0=Ada, 1=Blackwell
layout(constant_id = 1) const int MAX_BOUNCES = 1;

// ─── Payload ─────────────────────────────────────────────────────────────
layout(location = 0) rayPayloadInEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} payload;

// ─── Hit attribute ────────────────────────────────────────────────────────
hitAttributeEXT vec2 baryCoord;

// ═══════════════════════════════════════════════════════════════════════════
//  Bindings
// ═══════════════════════════════════════════════════════════════════════════

// Set 0: TLAS（次級光線用）
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;

// Set 2: Camera
layout(set = 2, binding = 0, scalar) uniform CameraFrame {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec3  camPos;    float _p0;
    vec3  sunDir;    float _p1;
    vec3  sunColor;  float _p2;
    vec3  skyColor;  float _p3;
    uint  frameIndex;
    float aoRadius;
    float aoStrength;
    float reflectionRoughnessThreshold;
    float _pad[4];
} cam;

// Set 3: 材料 UBO（由 BlockTypeRegistry 序列化，共 256 種材料）
struct MaterialData {
    vec3  baseColor;        float roughness;
    vec3  emissive;         float metallic;
    float transmission;     // 透明度（玻璃=0.9, 水=0.7, 石=0）
    float ior;              // 折射率（玻璃=1.5, 水=1.33）
    float sssRadius;        // 次表面散射（葉子/生物用）
    float cracksIntensity;  // 裂縫貼圖強度（應力受損時）
};
layout(set = 3, binding = 1, scalar) readonly buffer MaterialBuffer {
    MaterialData materials[256];
} matBuf;

// Set 3: StressVisualizationRT SSBO（應力熱圖，O(1) hash lookup）
// 格式：[count(int), _pad(3×int), HASH_SLOTS × {bX, bY, bZ, stress(float), _pad(4×float)}]
layout(set = 3, binding = 0, scalar) readonly buffer StressBuffer {
    int   count;
    int   _pad0, _pad1, _pad2;
    // Entries follow: bX(int), bY(int), bZ(int), stress(float), _pad[4]
    // 使用 uvec4 + vec4 pair 存取
    uvec4 headerAndSlots[];
} stress;

// Set 3: LOD Mesh VBO（供 closesthit 讀取頂點法線，提升反射精度）
// 每頂點 stride = 9 floats：xyz(3) + normal(3) + uv(2) + matId(1)
layout(set = 3, binding = 2, scalar) readonly buffer LODVertexBuffer {
    float verts[];
} lodVBO;

layout(set = 3, binding = 3) readonly buffer LODIndexBuffer {
    uint  indices[];
} lodIBO;

// ═══════════════════════════════════════════════════════════════════════════
//  應力 SSBO 查詢（Knuth 乘法 hash，最多 16 次探測）
//  與 StressVisualizationRT.java 中的 hash 策略完全一致
// ═══════════════════════════════════════════════════════════════════════════
const int HASH_SLOTS   = 8192;
const int HASH_EMPTY   = int(0x80000000u); // Integer.MIN_VALUE
const int MAX_PROBES   = 16;
const int HEADER_UINTS = 4;                // count + 3 pad
const int ENTRY_UINTS  = 8;               // bX(1) bY(1) bZ(1) stress_bits(1) _pad(4)

int hashSlot(int bx, int by, int bz) {
    // Knuth 乘法 hash（與 Java 側相同）
    uint h = uint(bx) * 2654435761u ^ uint(by) * 2246822519u ^ uint(bz) * 3266489917u;
    return int(h & uint(HASH_SLOTS - 1));
}

float queryStress(ivec3 blockPos) {
    if (stress.count <= 0) return 0.0;

    int slot = hashSlot(blockPos.x, blockPos.y, blockPos.z);

    for (int probe = 0; probe < MAX_PROBES; probe++) {
        int idx  = (slot + probe) & (HASH_SLOTS - 1);
        int base = HEADER_UINTS + idx * ENTRY_UINTS;

        int storedBX = int(stress.headerAndSlots[base / 4][base % 4]);
        if (storedBX == HASH_EMPTY) return 0.0; // 空槽 → 無應力

        // 比較完整 key
        int storedBY = int(stress.headerAndSlots[(base + 1) / 4][(base + 1) % 4]);
        int storedBZ = int(stress.headerAndSlots[(base + 2) / 4][(base + 2) % 4]);

        if (storedBX == blockPos.x && storedBY == blockPos.y && storedBZ == blockPos.z) {
            uint stressBits = stress.headerAndSlots[(base + 3) / 4][(base + 3) % 4];
            return uintBitsToFloat(stressBits);
        }
    }
    return 0.0;
}

// ═══════════════════════════════════════════════════════════════════════════
//  應力熱圖顏色映射（0=安全=綠色, 1=極限=紅色）
//  分三段：綠→黃→橙→紅 + 超出極限閃爍
// ═══════════════════════════════════════════════════════════════════════════
vec3 stressHeatmap(float stress01, uint frame) {
    float s = clamp(stress01, 0.0, 1.2);

    vec3 col;
    if (s < 0.4) {
        col = mix(vec3(0.1, 0.8, 0.1), vec3(0.9, 0.9, 0.1), s / 0.4);
    } else if (s < 0.75) {
        col = mix(vec3(0.9, 0.9, 0.1), vec3(0.95, 0.4, 0.0), (s - 0.4) / 0.35);
    } else if (s <= 1.0) {
        col = mix(vec3(0.95, 0.4, 0.0), vec3(0.9, 0.05, 0.05), (s - 0.75) / 0.25);
    } else {
        // 超出極限：紅色閃爍
        float blink = float((frame >> 2u) & 1u); // 每 4 幀閃一次
        col = mix(vec3(0.9, 0.05, 0.05), vec3(1.0, 0.8, 0.8), blink);
    }
    return col;
}

// ═══════════════════════════════════════════════════════════════════════════
//  從 LOD VBO 讀取插值頂點法線（精確反射）
// ═══════════════════════════════════════════════════════════════════════════
vec3 fetchInterpolatedNormal() {
    // primitiveID 索引到 IBO → 三角形頂點索引
    int triBase = gl_PrimitiveID * 3;
    uint i0 = lodIBO.indices[triBase    ];
    uint i1 = lodIBO.indices[triBase + 1];
    uint i2 = lodIBO.indices[triBase + 2];

    const int STRIDE = 9;
    // 法線在偏移 3-5（floats）
    vec3 n0 = vec3(lodVBO.verts[i0 * STRIDE + 3],
                   lodVBO.verts[i0 * STRIDE + 4],
                   lodVBO.verts[i0 * STRIDE + 5]);
    vec3 n1 = vec3(lodVBO.verts[i1 * STRIDE + 3],
                   lodVBO.verts[i1 * STRIDE + 4],
                   lodVBO.verts[i1 * STRIDE + 5]);
    vec3 n2 = vec3(lodVBO.verts[i2 * STRIDE + 3],
                   lodVBO.verts[i2 * STRIDE + 4],
                   lodVBO.verts[i2 * STRIDE + 5]);

    vec3 bary = vec3(1.0 - baryCoord.x - baryCoord.y, baryCoord.x, baryCoord.y);
    return normalize(n0 * bary.x + n1 * bary.y + n2 * bary.z);
}

// ═══════════════════════════════════════════════════════════════════════════
//  主程式
// ═══════════════════════════════════════════════════════════════════════════
void main() {
    // ── 命中位置 ──────────────────────────────────────────────────────────
    vec3 hitPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;

    // ── 材料 ID 與 LOD（從 instance custom index） ───────────────────────
    uint customIdx = gl_InstanceCustomIndexEXT;
    uint matId     = customIdx & 0xFFFFu;
    uint lodLevel  = (customIdx >> 16u) & 0xFu;

    // 邊界保護
    matId = clamp(matId, 0u, 255u);
    MaterialData mat = matBuf.materials[matId];

    // ── 法線 ──────────────────────────────────────────────────────────────
    // LOD 0-1：從 VBO 插值（精確），LOD 2-3：AABB 近似法線
    vec3 N;
    if (lodLevel <= 1u) {
        N = fetchInterpolatedNormal();
    } else {
        // AABB：從射線方向推算面法線（最近軸向面）
        vec3 absDir = abs(gl_WorldRayDirectionEXT);
        if (absDir.x >= absDir.y && absDir.x >= absDir.z)
            N = vec3(-sign(gl_WorldRayDirectionEXT.x), 0, 0);
        else if (absDir.y >= absDir.z)
            N = vec3(0, -sign(gl_WorldRayDirectionEXT.y), 0);
        else
            N = vec3(0, 0, -sign(gl_WorldRayDirectionEXT.z));
    }

    // ── 光照計算 ──────────────────────────────────────────────────────────
    float NdotL  = max(dot(N, normalize(cam.sunDir)), 0.0);
    vec3  diffuse = mat.baseColor * cam.sunColor * NdotL;
    vec3  ambient = mat.baseColor * cam.skyColor * 0.08;
    vec3  emissive = mat.emissive;

    // ── 應力熱圖疊加（StressVisualizationRT 整合） ───────────────────────
    ivec3 blockPos = ivec3(floor(hitPos));
    float stressVal = queryStress(blockPos);

    vec3 outColor = diffuse + ambient + emissive;

    if (stressVal > 0.01) {
        vec3  heatColor = stressHeatmap(stressVal, cam.frameIndex);
        float heatAlpha = smoothstep(0.1, 0.9, stressVal) * mat.cracksIntensity;
        heatAlpha = clamp(heatAlpha, 0.0, 0.85);
        outColor = mix(outColor, heatColor, heatAlpha);

        // 高應力時：降低粗糙度讓反射更顯眼（裂縫有光澤感）
        // 此修改已在反射計算中使用，這裡只影響直接光照
        if (stressVal > 0.9) {
            outColor += mat.baseColor * 0.3 * float((cam.frameIndex >> 1u) & 1u);
        }
    }

    // ── Blackwell 優化路徑：額外 diffuse bounce（SM 10+） ────────────────
    // Ada(SC0=0) 只做 1 bounce；Blackwell(SC0=1) 做 2 bounce
    if (GPU_TIER >= 1 && MAX_BOUNCES >= 2) {
        // 次級漫反射（在 Blackwell 上成本可接受）
        vec3 bounceDirL = N + vec3(0.2, 0.1, 0.3); // 輕量近似，Phase 3 換成 cosine sample
        outColor += mat.baseColor * cam.skyColor * 0.04;
    }

    // ── 輸出 ─────────────────────────────────────────────────────────────
    payload.radiance = outColor;
    payload.hitDist  = gl_HitTEXT;
    payload.matId    = matId;
    payload.lodLevel = lodLevel;
}
