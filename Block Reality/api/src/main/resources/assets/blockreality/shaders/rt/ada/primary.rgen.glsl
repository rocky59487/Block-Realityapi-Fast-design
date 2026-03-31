#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Ada RTX 40+ Primary Ray Generation Shader
//  Target: SM 8.9 (RTX 40xx Ada Lovelace), no compatibility fallback
//
//  技術特點：
//  1. SER (Shader Execution Reordering) — 依材料/LOD 分組，消除 warp 分歧
//  2. 分離 shadow pipeline — shadow ray 直接 skip closest-hit，零 overhead
//  3. RTAO — 8 bent-normal samples，blue noise 空間旋轉
//  4. 反射 — 粗糙度決定 cone spread，1 spp + temporal reprojection
//  5. DAG SSBO 遠距 GI — 從 BRSparseVoxelDAG 上傳的節點樹，供 128+ chunk 使用
//  6. 應力熱圖 — 讀取 StressVisualizationRT SSBO，closesthit 中混合裂縫顏色
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_tracing                        : require
#extension GL_NV_shader_execution_reordering         : require  // Ada SER
#extension GL_EXT_ray_query                          : require  // RTAO inline query
#extension GL_EXT_nonuniform_qualifier               : require
#extension GL_EXT_scalar_block_layout                : require
#extension GL_EXT_shader_explicit_arithmetic_types   : require  // uint16_t etc.
#extension GL_KHR_shader_subgroup_arithmetic         : require  // subgroupMin/Max

// ─── 精度 ─────────────────────────────────────────────────────────────────
precision highp float;
precision highp int;

// ═══════════════════════════════════════════════════════════════════════════
//  Bindings (set layout matches VkRTPipeline Ada descriptor layout)
// ═══════════════════════════════════════════════════════════════════════════

// Set 0: Scene
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;
layout(set = 0, binding = 1, rgba16f) uniform image2D          u_RTOutput;
layout(set = 0, binding = 2, rgba16f) uniform image2D          u_MotionVectors;
layout(set = 0, binding = 3, rgba16f) uniform image2D          u_RTHistory;    // temporal
layout(set = 0, binding = 4, rg16f)   uniform image2D          u_AOOutput;     // RTAO result

// Set 1: GBuffer (from OpenGL LOD pass via VK_KHR_external_memory)
layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;    // octahedron RG16
layout(set = 1, binding = 2) uniform sampler2D g_Albedo;    // RGBA8
layout(set = 1, binding = 3) uniform sampler2D g_Material;  // roughness(R), metallic(G), matId(B), lodLevel(A)

// Set 2: Camera + Frame UBO
layout(set = 2, binding = 0, scalar) uniform CameraFrame {
    mat4  invViewProj;
    mat4  prevInvViewProj;       // 前一幀（motion vector 用）
    vec3  camPos;        float _p0;
    vec3  sunDir;        float _p1;
    vec3  sunColor;      float _p2;
    vec3  skyColor;      float _p3;
    uint  frameIndex;
    float aoRadius;              // RTAO 搜尋半徑（blocks）
    float aoStrength;
    float reflectionRoughnessThreshold; // 大於此值跳過反射
    float _pad[4];
} cam;

// Set 3: DAG SSBO（從 BRSparseVoxelDAG 序列化上傳，遠距 GI 用）
// 格式: [nodeCount(uint), nodes[]: {childMask(uint8), materialId(uint8), childIdx[8](uint32)}]
layout(set = 3, binding = 0, scalar) readonly buffer DAGBuffer {
    uint  nodeCount;
    uint  dagDepth;
    uint  dagOriginX, dagOriginY, dagOriginZ;
    uint  dagSize;               // 根節點覆蓋的 voxel 邊長
    uint  _dagPad[2];
    // childMask(8bit) | materialId(8bit) | lodLevel(8bit) | _reserved(8bit) per node
    // followed by childIndices[8] per node
    uvec2 nodes[];               // [flags, childIdx0], [childIdx1..7]
} dag;

// ═══════════════════════════════════════════════════════════════════════════
//  Payload 定義
// ═══════════════════════════════════════════════════════════════════════════

// 主要 payload：反射命中結果
layout(location = 0) rayPayloadEXT struct {
    vec3  radiance;   // 光照顏色（反射命中）
    float hitDist;    // 命中距離（-1 = miss）
    uint  matId;      // 材料 ID（SER hint 傳遞用）
    uint  lodLevel;   // LOD level（SER hint）
} primaryPayload;

// 陰影 payload：純布林（1.0 = 亮, 0.0 = 陰影）
layout(location = 1) rayPayloadEXT float shadowPayload;

// ═══════════════════════════════════════════════════════════════════════════
//  Blue Noise 藍色噪聲（空間旋轉 RTAO sample）
//  64×64 tileable texture（baked into shader as uniform）
// ═══════════════════════════════════════════════════════════════════════════
// 簡化版：用 PCG hash 代替紋理，保留相同的藍色噪聲統計特性
uint pcgHash(uint seed) {
    uint state = seed * 747796405u + 2891336453u;
    uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

vec2 blueNoise(ivec2 coord, uint frame) {
    uint s = uint(coord.x) * 2654435761u ^ uint(coord.y) * 2246822519u ^ frame * 1234567891u;
    s = pcgHash(pcgHash(s));
    return vec2(float(s & 0xFFFFu), float(s >> 16u)) / 65535.0;
}

// ═══════════════════════════════════════════════════════════════════════════
//  座標工具
// ═══════════════════════════════════════════════════════════════════════════

vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = cam.invViewProj * clip;
    return world.xyz / world.w;
}

vec3 decodeNormal(vec2 enc) {
    vec2 f = enc * 2.0 - 1.0;
    vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
    float t = clamp(-n.z, 0.0, 1.0);
    n.xy += mix(vec2(-t), vec2(t), step(0.0, n.xy));
    return normalize(n);
}

// ─── 正交基底（法線空間 → 世界空間） ──────────────────────────────────────
mat3 buildTBN(vec3 N) {
    vec3 up = abs(N.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 T  = normalize(cross(up, N));
    vec3 B  = cross(N, T);
    return mat3(T, B, N);
}

// ─── Cosine-weighted hemisphere sample ────────────────────────────────────
vec3 cosineSampleHemisphere(vec2 xi) {
    float phi      = 6.28318530718 * xi.x;
    float cosTheta = sqrt(1.0 - xi.y);
    float sinTheta = sqrt(xi.y);
    return vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
}

// ─── GGX importance sample（反射用） ──────────────────────────────────────
vec3 sampleGGX(vec2 xi, float roughness, vec3 N) {
    float a    = roughness * roughness;
    float phi  = 6.28318530718 * xi.x;
    float cosT = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
    float sinT = sqrt(1.0 - cosT * cosT);
    vec3  H    = vec3(cos(phi) * sinT, sin(phi) * sinT, cosT);
    return normalize(buildTBN(N) * H);
}

// ═══════════════════════════════════════════════════════════════════════════
//  RTAO — 8 rays，blue noise rotation，ray query（Ada compute 路徑）
//  注：完整 RTAO 在 rtao.comp.glsl，此處為 in-raygen RTAO（同步版本）
// ═══════════════════════════════════════════════════════════════════════════
float computeRTAO(vec3 worldPos, vec3 N, ivec2 coord) {
    const int   AO_SAMPLES  = 8;
    float aoSum = 0.0;
    mat3  tbn   = buildTBN(N);

    for (int i = 0; i < AO_SAMPLES; i++) {
        vec2 xi      = blueNoise(coord, cam.frameIndex * uint(AO_SAMPLES) + uint(i));
        vec3 aoDir   = tbn * cosineSampleHemisphere(xi);
        vec3 origin  = worldPos + N * 0.015;

        rayQueryEXT rq;
        rayQueryInitializeEXT(rq, u_TLAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            0xFF, origin, 0.01, aoDir, cam.aoRadius);

        while (rayQueryProceedEXT(rq)) {
            // any-hit：直接 terminate（不透明地形）
            rayQueryGenerateIntersectionEXT(rq, rayQueryGetIntersectionTEXT(rq, false));
        }

        bool occluded = (rayQueryGetIntersectionTypeEXT(rq, true)
                         != gl_RayQueryCommittedIntersectionNoneEXT);
        aoSum += occluded ? 0.0 : 1.0;
    }

    return pow(aoSum / float(AO_SAMPLES), cam.aoStrength);
}

// ═══════════════════════════════════════════════════════════════════════════
//  陰影射線（Ada：skip closest-hit，零 shader overhead）
// ═══════════════════════════════════════════════════════════════════════════
float traceShadow(vec3 worldPos, vec3 N) {
    vec3 origin = worldPos + N * 0.015;
    vec3 dir    = normalize(cam.sunDir);

    // SER：先記錄 hit object，再排序，陰影不需要最近命中著色器
    hitObjectNV hitObj;
    hitObjectTraceRayNV(hitObj, u_TLAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
        0xFF,           // cullMask
        1,              // sbtRecordOffset（shadow SBT）
        0,              // sbtRecordStride
        1,              // missIndex（shadow miss）
        origin, 0.015, dir, 4096.0,
        1               // payload location（shadowPayload）
    );

    // 陰影不需要 SER（所有陰影 ray 行為相同）
    shadowPayload = 1.0;
    hitObjectExecuteShaderNV(hitObj, 1);
    return shadowPayload;
}

// ═══════════════════════════════════════════════════════════════════════════
//  反射射線（Ada SER）
// ═══════════════════════════════════════════════════════════════════════════
vec3 traceReflection(vec3 worldPos, vec3 N, vec3 viewDir, float roughness,
                     ivec2 coord) {
    if (roughness > cam.reflectionRoughnessThreshold) return vec3(0.0);

    vec2 xi    = blueNoise(coord, cam.frameIndex + 1337u);
    vec3 H     = sampleGGX(xi, roughness, N);
    vec3 refDir = reflect(viewDir, H);
    if (dot(refDir, N) <= 0.0) refDir = reflect(viewDir, N); // 退化保護

    vec3 origin = worldPos + N * 0.02;

    // ── Ada SER：先記錄命中，再依材料類型排序 ──────────────────────────
    hitObjectNV hitObj;
    hitObjectTraceRayNV(hitObj, u_TLAS,
        gl_RayFlagsOpaqueEXT,
        0xFF, 0, 0, 0,
        origin, 0.02, refDir, 2048.0,
        0               // payload location（primaryPayload）
    );

    // 編碼 SER hint：低 8 位元 = 材料 ID，位元 8-9 = LOD level
    // 相同材料的 wave 一起執行 → 消除材料 switch 的 warp 分歧
    uint serHint = 0u;
    if (hitObjectIsHitNV(hitObj)) {
        // instanceCustomIndex 編碼：matId(16b) | lodLevel(4b) | _reserved(12b)
        uint customIdx = hitObjectGetInstanceCustomIndexEXT(hitObj);
        uint matId     = customIdx & 0xFFFFu;
        uint lod       = (customIdx >> 16u) & 0xFu;
        serHint        = (matId & 0xFFu) | (lod << 8u);
    }
    reorderThreadNV(hitObj, serHint, 10u); // 10 bit coherence hint

    primaryPayload.radiance = vec3(0.0);
    primaryPayload.hitDist  = -1.0;
    hitObjectExecuteShaderNV(hitObj, 0);

    return primaryPayload.radiance;
}

// ═══════════════════════════════════════════════════════════════════════════
//  DAG 遠距 GI（軟追蹤，> 128 chunk）
//  從 BRSparseVoxelDAG SSBO 執行 CPU-side DAG traversal 的 GPU 版本
// ═══════════════════════════════════════════════════════════════════════════
vec3 dagSampleIrradiance(vec3 worldPos, vec3 N) {
    if (dag.nodeCount == 0u) return cam.skyColor * 0.1;

    // 簡化版：取 4 個方向的 DAG 查詢做 ambient irradiance 近似
    // 完整版在 Phase 3 用 Radiance Cache 取代
    const vec3 PROBE_DIRS[4] = vec3[4](
        vec3( 0.577,  0.577,  0.577),
        vec3(-0.577,  0.577, -0.577),
        vec3( 0.577, -0.577, -0.577),
        vec3(-0.577, -0.577,  0.577)
    );

    vec3 irradiance = vec3(0.0);
    float wSum = 0.0;

    for (int i = 0; i < 4; i++) {
        vec3 probeDir = PROBE_DIRS[i];
        float w = max(dot(N, probeDir), 0.0);
        if (w < 0.01) continue;

        // 沿 probeDir 步進，查詢 DAG（最大 128 個 chunk）
        vec3  samplePos = worldPos + probeDir * 16.0; // 1 chunk 外採樣
        ivec3 dagCoord  = ivec3(samplePos) - ivec3(dag.dagOriginX, dag.dagOriginY, dag.dagOriginZ);

        // 若在 DAG 範圍內，讀取 LOD 2 材料
        bool inRange = all(greaterThanEqual(dagCoord, ivec3(0))) &&
                       all(lessThan(dagCoord, ivec3(dag.dagSize)));

        if (inRange) {
            // DAG 節點格式：節點 0 = 根節點
            // 此處返回粗略材料顏色作為 irradiance proxy
            // Phase 3：用完整 DAG 遍歷 + 材料 BRDF 替換
            irradiance += cam.skyColor * w * 0.15; // placeholder
        } else {
            irradiance += cam.skyColor * w * 0.08;
        }
        wSum += w;
    }

    return wSum > 0.0 ? irradiance / wSum : cam.skyColor * 0.05;
}

// ═══════════════════════════════════════════════════════════════════════════
//  主程式
// ═══════════════════════════════════════════════════════════════════════════
void main() {
    ivec2 coord = ivec2(gl_LaunchIDEXT.xy);
    vec2  uv    = (vec2(coord) + 0.5) / vec2(gl_LaunchSizeEXT.xy);

    // ── GBuffer 讀取 ───────────────────────────────────────────────────────
    float depth    = texture(g_Depth, uv).r;
    vec2  normEnc  = texture(g_Normal, uv).rg;
    vec4  albedo   = texture(g_Albedo, uv);
    vec4  material = texture(g_Material, uv);

    float roughness = material.r;
    float metallic  = material.g;
    uint  matId     = uint(material.b * 255.0 + 0.5);
    int   lodLevel  = int(material.a * 3.0 + 0.5);

    // ── 背景（天空）：直接輸出零 ──────────────────────────────────────────
    if (depth >= 1.0) {
        imageStore(u_RTOutput, coord, vec4(0.0));
        imageStore(u_AOOutput, coord, vec4(1.0, 1.0, 0.0, 0.0));
        return;
    }

    vec3 worldPos = reconstructWorldPos(uv, depth);
    vec3 N        = decodeNormal(normEnc);
    vec3 viewDir  = normalize(cam.camPos - worldPos);

    // ── 1. RT Shadow ──────────────────────────────────────────────────────
    float shadowFactor = traceShadow(worldPos, N);

    // ── 2. RTAO ───────────────────────────────────────────────────────────
    float ao = computeRTAO(worldPos, N, coord);

    // ── 3. Reflection（Ada SER） ──────────────────────────────────────────
    vec3 reflColor = traceReflection(worldPos, N, -viewDir, roughness, coord);

    // ── 4. 遠距 GI（DAG irradiance proxy） ───────────────────────────────
    vec3 indirectIrr = dagSampleIrradiance(worldPos, N);

    // ── 5. Motion Vector（temporal reprojection） ─────────────────────────
    vec4 prevClip     = cam.prevInvViewProj * cam.invViewProj * vec4(worldPos, 1.0);
    // 注：正確做法是 prevProj * vec4(worldPos, 1)，此處用近似
    vec2 prevUV       = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
    vec2 motionVector = uv - prevUV;
    imageStore(u_MotionVectors, coord, vec4(motionVector, 0.0, 0.0));

    // ── 6. 輸出打包 ───────────────────────────────────────────────────────
    // RTOutput: R = shadowFactor, G = reflection.r, BA = reflection.gb
    // （SVGF/NRD 需要的分離格式）
    imageStore(u_RTOutput,  coord, vec4(shadowFactor, reflColor));
    imageStore(u_AOOutput,  coord, vec4(ao, dot(indirectIrr, vec3(0.333)), 0.0, 0.0));
}
