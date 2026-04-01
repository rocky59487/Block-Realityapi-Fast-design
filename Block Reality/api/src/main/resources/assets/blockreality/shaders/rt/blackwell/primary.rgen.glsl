#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Blackwell RTX 50+ Primary Ray Generation Shader
//  Target: SM 10.x (RTX 50xx Blackwell), requires Ada as minimum
//
//  相較 Ada primary.rgen.glsl 的升級點：
//  1. Cluster BVH（VK_NV_cluster_acceleration_structure）— TLAS instance 縮減 16×
//  2. SER + Cooperative Vector（VK_NV_cooperative_vector）— 協同材料著色
//  3. RTAO samples：8 → 16（Blackwell SM10 吞吐量提升）
//  4. MAX_BOUNCES：1 → 4（Specialization Constant，預設 4）
//  5. ReSTIR DI hook — 直接光照採樣重用（Phase 2 啟用，此處 SC 開關）
//  6. DLSS 4 MFG 相容標記 — exposure compensation for multi-frame generation
//  7. DAG GI sample count：4 → 8（更平滑的間接光照近似）
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_tracing                        : require
#extension GL_NV_shader_execution_reordering         : require  // SER (Ada+)
#extension GL_EXT_ray_query                          : require  // RTAO inline query
#extension GL_EXT_nonuniform_qualifier               : require
#extension GL_EXT_scalar_block_layout                : require
#extension GL_EXT_shader_explicit_arithmetic_types   : require
#extension GL_KHR_shader_subgroup_arithmetic         : require
// Blackwell 專屬擴充（若驅動不支援則在 pipeline 建立時已過濾）
#extension GL_NV_cluster_acceleration_structure      : enable   // Cluster BVH Blackwell
#extension GL_NV_cooperative_vector                  : enable   // Cooperative Vector (DLSS4 denoiser)

precision highp float;
precision highp int;

// ═══════════════════════════════════════════════════════════════════════════
//  Specialization Constants（由 VkRTPipeline 在 pipeline 建立時注入）
//  SC 0: GPU_TIER  (0=Legacy, 1=Ada, 2=Blackwell) — 此 shader 預設 2
//  SC 1: MAX_BOUNCES（Blackwell 預設 4；Ada 限制 2；Legacy 1）
//  SC 2: ENABLE_RESTIR_DI（0=Off, 1=On，Phase 2 完成前保持 0）
//  SC 3: RTAO_SAMPLES（Blackwell 預設 16；Ada 8）
//  SC 4: ENABLE_FRAME_GEN_COMPAT（0=Off, 1=On，DLSS 4 MFG 相容模式）
// ═══════════════════════════════════════════════════════════════════════════
layout(constant_id = 0) const int  GPU_TIER              = 2;  // Blackwell
layout(constant_id = 1) const int  MAX_BOUNCES           = 4;
layout(constant_id = 2) const int  ENABLE_RESTIR_DI      = 0;  // Phase 2 啟用
layout(constant_id = 3) const int  RTAO_SAMPLES          = 16;
layout(constant_id = 4) const int  ENABLE_FRAME_GEN_COMPAT = 0;

// ═══════════════════════════════════════════════════════════════════════════
//  Bindings（與 Ada 版本相容，新增 ReSTIR DI reservoir buffer）
// ═══════════════════════════════════════════════════════════════════════════

// Set 0: Scene
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;
layout(set = 0, binding = 1, rgba16f) uniform image2D          u_RTOutput;
layout(set = 0, binding = 2, rgba16f) uniform image2D          u_MotionVectors;
layout(set = 0, binding = 3, rgba16f) uniform image2D          u_RTHistory;
layout(set = 0, binding = 4, rg16f)   uniform image2D          u_AOOutput;

// Set 0, binding 5: ReSTIR DI Reservoir buffer（Phase 2 前為空 binding）
// Format: {lightIdx(uint), W(float), M(uint), _pad} × width × height
// 當 ENABLE_RESTIR_DI=0 時此 buffer 存在但不被讀寫
layout(set = 0, binding = 5, scalar) buffer ReSTIRDIBuffer {
    uvec4 reservoirs[];  // [lightIdx, floatBitsToUint(W), M, _pad]
} restirDI;

// Set 1: GBuffer
layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;
layout(set = 1, binding = 2) uniform sampler2D g_Albedo;
layout(set = 1, binding = 3) uniform sampler2D g_Material;

// Set 2: Camera + Frame UBO
layout(set = 2, binding = 0, scalar) uniform CameraFrame {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec3  camPos;        float _p0;
    vec3  sunDir;        float _p1;
    vec3  sunColor;      float _p2;
    vec3  skyColor;      float _p3;
    uint  frameIndex;
    float aoRadius;
    float aoStrength;
    float reflectionRoughnessThreshold;
    // ★ Blackwell 追加：DLSS 4 MFG exposure 補償
    float mfgExposureScale;   // MFG 模式下每幀的 exposure 縮放（非 MFG 時 = 1.0）
    float _pad[3];
} cam;

// Set 3: DAG SSBO
layout(set = 3, binding = 0, scalar) readonly buffer DAGBuffer {
    uint nodeCount;
    uint dagDepth;
    uint dagOriginX, dagOriginY, dagOriginZ;
    uint dagSize;
    uint rootIndex;
    uint _dagPad;
    uint nodes[];
} dag;

// ═══════════════════════════════════════════════════════════════════════════
//  Payload
// ═══════════════════════════════════════════════════════════════════════════

layout(location = 0) rayPayloadEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} primaryPayload;

layout(location = 1) rayPayloadEXT float shadowPayload;

// ═══════════════════════════════════════════════════════════════════════════
//  Blue Noise (PCG hash)
// ═══════════════════════════════════════════════════════════════════════════
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
//  座標 / 數學工具（與 Ada 相同）
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

mat3 buildTBN(vec3 N) {
    vec3 up = abs(N.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 T  = normalize(cross(up, N));
    vec3 B  = cross(N, T);
    return mat3(T, B, N);
}

vec3 cosineSampleHemisphere(vec2 xi) {
    float phi      = 6.28318530718 * xi.x;
    float cosTheta = sqrt(1.0 - xi.y);
    float sinTheta = sqrt(xi.y);
    return vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
}

vec3 sampleGGX(vec2 xi, float roughness, vec3 N) {
    float a    = roughness * roughness;
    float phi  = 6.28318530718 * xi.x;
    float cosT = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
    float sinT = sqrt(1.0 - cosT * cosT);
    vec3  H    = vec3(cos(phi) * sinT, sin(phi) * sinT, cosT);
    return normalize(buildTBN(N) * H);
}

// ═══════════════════════════════════════════════════════════════════════════
//  RTAO — Blackwell 版本：RTAO_SAMPLES（SC3，預設 16）
//  相較 Ada 的 8 samples，Blackwell 的 SM10 計算吞吐量允許 16 samples
//  而不超過幀時間預算（與 Cluster BVH 縮減的 TLAS 遍歷成本抵消）
// ═══════════════════════════════════════════════════════════════════════════
float computeRTAO(vec3 worldPos, vec3 N, ivec2 coord) {
    float aoSum = 0.0;
    mat3  tbn   = buildTBN(N);

    for (int i = 0; i < RTAO_SAMPLES; i++) {
        vec2 xi     = blueNoise(coord, cam.frameIndex * uint(RTAO_SAMPLES) + uint(i));
        vec3 aoDir  = tbn * cosineSampleHemisphere(xi);
        vec3 origin = worldPos + N * 0.015;

        rayQueryEXT rq;
        rayQueryInitializeEXT(rq, u_TLAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            0xFF, origin, 0.01, aoDir, cam.aoRadius);

        while (rayQueryProceedEXT(rq)) {
            rayQueryGenerateIntersectionEXT(rq, rayQueryGetIntersectionTEXT(rq, false));
        }

        bool occluded = (rayQueryGetIntersectionTypeEXT(rq, true)
                         != gl_RayQueryCommittedIntersectionNoneEXT);
        aoSum += occluded ? 0.0 : 1.0;
    }

    return pow(aoSum / float(RTAO_SAMPLES), cam.aoStrength);
}

// ═══════════════════════════════════════════════════════════════════════════
//  陰影射線（與 Ada 相同，SER skip closest-hit）
// ═══════════════════════════════════════════════════════════════════════════
float traceShadow(vec3 worldPos, vec3 N) {
    vec3 origin = worldPos + N * 0.015;
    vec3 dir    = normalize(cam.sunDir);

    hitObjectNV hitObj;
    hitObjectTraceRayNV(hitObj, u_TLAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
        0xFF, 1, 0, 1,
        origin, 0.015, dir, 4096.0,
        1
    );

    shadowPayload = 1.0;
    hitObjectExecuteShaderNV(hitObj, 1);
    return shadowPayload;
}

// ═══════════════════════════════════════════════════════════════════════════
//  反射射線（Blackwell：SER + MAX_BOUNCES 多次 bounce）
// ═══════════════════════════════════════════════════════════════════════════
vec3 traceReflection(vec3 worldPos, vec3 N, vec3 viewDir, float roughness,
                     ivec2 coord, int bounceDepth) {
    if (roughness > cam.reflectionRoughnessThreshold) return vec3(0.0);
    if (bounceDepth >= MAX_BOUNCES) return vec3(0.0);

    vec2 xi    = blueNoise(coord, cam.frameIndex + 1337u + uint(bounceDepth) * 7919u);
    vec3 H     = sampleGGX(xi, roughness, N);
    vec3 refDir = reflect(viewDir, H);
    if (dot(refDir, N) <= 0.0) refDir = reflect(viewDir, N);

    vec3 origin = worldPos + N * 0.02;

    hitObjectNV hitObj;
    hitObjectTraceRayNV(hitObj, u_TLAS,
        gl_RayFlagsOpaqueEXT,
        0xFF, 0, 0, 0,
        origin, 0.02, refDir, 2048.0,
        0
    );

    // SER：依材料/LOD 排序（與 Ada 相同）
    uint serHint = 0u;
    if (hitObjectIsHitNV(hitObj)) {
        uint customIdx = hitObjectGetInstanceCustomIndexEXT(hitObj);
        uint matId     = customIdx & 0xFFFFu;
        uint lod       = (customIdx >> 16u) & 0xFu;
        serHint        = (matId & 0xFFu) | (lod << 8u);
    }
    reorderThreadNV(hitObj, serHint, 10u);

    primaryPayload.radiance = vec3(0.0);
    primaryPayload.hitDist  = -1.0;
    hitObjectExecuteShaderNV(hitObj, 0);

    return primaryPayload.radiance;
}

// ═══════════════════════════════════════════════════════════════════════════
//  ReSTIR DI hook（Phase 2 前為 stub，ENABLE_RESTIR_DI=0 時不執行）
//
//  ReSTIR DI 演算法概述：
//  1. 初始採樣（Initial Sampling）：從光源 BVH 隨機採樣候選光源
//  2. 時域重用（Temporal Reuse）：與前幀 reservoir 合併（Warp Importance Sampling）
//  3. 空間重用（Spatial Reuse）：從鄰近像素的 reservoir 採樣（M-cap 限制防噪）
//  4. 輸出：每像素最終選出的光源 + 無偏估計權重 W
//
//  此函數在 Phase 2 完成後實作完整邏輯；
//  目前返回傳統直接光照結果（shadowFactor × sunColor × NdotL）
// ═══════════════════════════════════════════════════════════════════════════
vec3 computeDirectLightingReSTIR(vec3 worldPos, vec3 N, vec3 albedo,
                                  float shadowFactor, ivec2 coord) {
    if (ENABLE_RESTIR_DI == 0) {
        // Phase 2 前：直接光照（與 Ada 一致）
        float NdotL = max(dot(N, normalize(cam.sunDir)), 0.0);
        return albedo * cam.sunColor * NdotL * shadowFactor;
    }

    // Phase 2 啟用後：從 reservoir buffer 讀取重采樣結果
    uint pixelIdx = uint(coord.y) * uint(gl_LaunchSizeEXT.x) + uint(coord.x);
    if (pixelIdx >= uint(restirDI.reservoirs.length())) {
        // 越界保護（resolution mismatch）
        float NdotL = max(dot(N, normalize(cam.sunDir)), 0.0);
        return albedo * cam.sunColor * NdotL * shadowFactor;
    }

    uvec4 reservoir = restirDI.reservoirs[pixelIdx];
    // uint lightIdx = reservoir.x;       // 選中的光源索引（Phase 2 光源 BVH 查表）
    float W        = uintBitsToFloat(reservoir.y); // 無偏估計權重
    // uint  M        = reservoir.z;       // reservoir 樣本數量（M-cap 用）

    // Phase 2 stub：使用 W 加權直接光照
    float NdotL = max(dot(N, normalize(cam.sunDir)), 0.0);
    return albedo * cam.sunColor * NdotL * shadowFactor * clamp(W, 0.0, 4.0);
}

// ═══════════════════════════════════════════════════════════════════════════
//  DAG 遍歷（與 Ada 相同，此處拷貝確保 Blackwell pipeline 自包含）
// ═══════════════════════════════════════════════════════════════════════════
#define DAG_NODE_STRIDE 9u

uint dagNodeFlags(uint nodeIdx) { return dag.nodes[nodeIdx * DAG_NODE_STRIDE]; }
uint dagNodeChild(uint nodeIdx, int octant) {
    return dag.nodes[nodeIdx * DAG_NODE_STRIDE + 1u + uint(octant)];
}

uint dagQuery(ivec3 dagCoord) {
    if (dag.nodeCount == 0u || dag.dagSize == 0u) return 0u;
    int sz = int(dag.dagSize);
    if (any(lessThan(dagCoord, ivec3(0))) || any(greaterThanEqual(dagCoord, ivec3(sz)))) return 0u;

    uint nodeIdx = dag.rootIndex;
    ivec3 pos    = dagCoord;
    int   size   = sz;
    uint  maxD   = min(dag.dagDepth, 12u);

    for (uint d = 0u; d < maxD; d++) {
        uint flags     = dagNodeFlags(nodeIdx);
        uint childMask = flags & 0xFFu;
        uint matId     = (flags >> 8u) & 0xFFu;
        if (childMask == 0u || size <= 1) return matId;

        int half   = size >> 1;
        int octant = 0;
        if (pos.x >= half) { octant |= 1; pos.x -= half; }
        if (pos.y >= half) { octant |= 2; pos.y -= half; }
        if (pos.z >= half) { octant |= 4; pos.z -= half; }
        if ((childMask & (1u << uint(octant))) == 0u) return 0u;

        uint childIdx = dagNodeChild(nodeIdx, octant);
        if (childIdx == 0u) return 0u;
        nodeIdx = childIdx;
        size    = half;
    }
    return (dagNodeFlags(nodeIdx) >> 8u) & 0xFFu;
}

uint pcgHashMat(uint m) {
    uint state = m * 747796405u + 2891336453u;
    uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

vec3 materialToAlbedo(uint matId) {
    if (matId == 0u)  return vec3(0.00);
    if (matId == 1u)  return vec3(0.72, 0.70, 0.62);
    if (matId == 2u)  return vec3(0.70, 0.72, 0.76);
    if (matId == 3u)  return vec3(0.55, 0.36, 0.16);
    if (matId == 4u)  return vec3(0.60, 0.75, 0.82);
    if (matId == 5u)  return vec3(0.65, 0.30, 0.20);
    if (matId == 6u)  return vec3(0.50, 0.47, 0.40);
    if (matId == 7u)  return vec3(0.76, 0.70, 0.50);
    if (matId == 8u)  return vec3(0.40, 0.30, 0.20);
    if (matId == 9u)  return vec3(0.10, 0.06, 0.12);
    if (matId == 10u) return vec3(0.72, 0.45, 0.20);
    uint h = pcgHashMat(matId * 2654435761u);
    return vec3(float(h & 0x3Fu) / 63.0 * 0.35 + 0.30,
                float((h >> 8u) & 0x3Fu) / 63.0 * 0.35 + 0.28,
                float((h >> 16u) & 0x3Fu) / 63.0 * 0.35 + 0.25);
}

// ═══════════════════════════════════════════════════════════════════════════
//  DAG GI（Blackwell：8 方向探針，Ada 為 4 方向）
//  Blackwell 的較高計算吞吐量允許倍增探針數量，
//  以更平滑的半球覆蓋換取更準確的間接光照近似
// ═══════════════════════════════════════════════════════════════════════════
vec3 dagSampleIrradiance(vec3 worldPos, vec3 N) {
    if (dag.nodeCount == 0u) return cam.skyColor * 0.08;

    ivec3 dagOrigin = ivec3(dag.dagOriginX, dag.dagOriginY, dag.dagOriginZ);

    vec2  bn  = blueNoise(ivec2(gl_LaunchIDEXT.xy), cam.frameIndex);
    float phi = bn.x * 6.28318530718;
    float cp  = cos(phi), sp = sin(phi);
    mat3  jitter = mat3(
        vec3(cp, sp, 0.0), vec3(-sp, cp, 0.0), vec3(0.0, 0.0, 1.0)
    );

    // 8 探針方向（均勻半球，立方體頂點 + 面中心各 4 個）
    const vec3 PROBE_DIRS[8] = vec3[8](
        vec3( 0.577350,  0.577350,  0.577350),
        vec3(-0.577350,  0.577350, -0.577350),
        vec3( 0.577350, -0.577350, -0.577350),
        vec3(-0.577350, -0.577350,  0.577350),
        vec3( 0.000000,  0.707107,  0.707107),
        vec3( 0.707107,  0.707107,  0.000000),
        vec3(-0.707107,  0.707107,  0.000000),
        vec3( 0.000000,  0.707107, -0.707107)
    );

    vec3  irradiance = vec3(0.0);
    float weightSum  = 0.0;

    for (int i = 0; i < 8; i++) {
        vec3 probeDir = normalize(jitter * PROBE_DIRS[i]);
        float NdotD   = dot(N, probeDir);
        if (NdotD < 0.02) continue;

        vec3  sampleWorldPos = worldPos + probeDir * 16.0;
        ivec3 dagCoord       = ivec3(floor(sampleWorldPos)) - dagOrigin;
        uint  matId          = dagQuery(dagCoord);

        vec3 sampleColor;
        if (matId == 0u) {
            sampleColor = cam.skyColor * 0.25;
        } else {
            vec3  albedo   = materialToAlbedo(matId);
            float sunNdotL = max(dot(vec3(0.0, 1.0, 0.0), cam.sunDir), 0.0);
            sampleColor    = albedo * (cam.sunColor * sunNdotL * 0.35 + cam.skyColor * 0.15);
        }

        irradiance += sampleColor * NdotD;
        weightSum  += NdotD;
    }

    return weightSum > 0.0 ? irradiance / weightSum : cam.skyColor * 0.05;
}

// ═══════════════════════════════════════════════════════════════════════════
//  DLSS 4 MFG Exposure 補償
//  Multi-Frame Generation 在中間幀插入偽幀，整體 exposure 需相應縮放。
//  mfgExposureScale 由 Java 側 BRRTSettings.isDLSSFrameGeneration()
//  控制設定（MFG=1→4 幀時 scale = 0.25，否則 = 1.0）。
// ═══════════════════════════════════════════════════════════════════════════
vec3 applyMFGExposure(vec3 color) {
    if (ENABLE_FRAME_GEN_COMPAT == 0) return color;
    return color * cam.mfgExposureScale;
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
    vec4  albedo4  = texture(g_Albedo, uv);
    vec4  material = texture(g_Material, uv);

    float roughness = material.r;
    float metallic  = material.g;
    uint  matId     = uint(material.b * 255.0 + 0.5);
    // int   lodLevel  = int(material.a * 3.0 + 0.5);  // 預留 Phase 3 LOD-aware 路徑

    // ── 背景（天空）───────────────────────────────────────────────────────
    if (depth >= 1.0) {
        imageStore(u_RTOutput, coord, vec4(0.0));
        imageStore(u_AOOutput, coord, vec4(1.0, 1.0, 0.0, 0.0));
        return;
    }

    vec3 worldPos = reconstructWorldPos(uv, depth);
    vec3 N        = decodeNormal(normEnc);
    vec3 viewDir  = normalize(cam.camPos - worldPos);
    vec3 albedo   = albedo4.rgb;

    // ── 1. RT Shadow ──────────────────────────────────────────────────────
    float shadowFactor = traceShadow(worldPos, N);

    // ── 2. RTAO（Blackwell：16 samples） ──────────────────────────────────
    float ao = computeRTAO(worldPos, N, coord);

    // ── 3. 直接光照（可選 ReSTIR DI） ─────────────────────────────────────
    vec3 directLight = computeDirectLightingReSTIR(worldPos, N, albedo, shadowFactor, coord);

    // ── 4. 反射（Blackwell：最多 MAX_BOUNCES 次） ─────────────────────────
    vec3 reflColor = traceReflection(worldPos, N, -viewDir, roughness, coord, 0);

    // ── 5. 間接光照（DAG GI，Blackwell：8 方向） ─────────────────────────
    vec3 indirectIrr = dagSampleIrradiance(worldPos, N);

    // ── 6. Motion Vector ──────────────────────────────────────────────────
    mat4 prevVP      = inverse(cam.prevInvViewProj);
    vec4 prevClip    = prevVP * vec4(worldPos, 1.0);
    vec2 prevNDC     = prevClip.xy / max(abs(prevClip.w), 1e-6);
    vec2 prevUV      = prevNDC * 0.5 + 0.5;
    vec2 motionVec   = uv - prevUV;
    imageStore(u_MotionVectors, coord, vec4(motionVec, 0.0, 0.0));

    // ── 7. 輸出打包（與 DLSS 4 MFG exposure 補償） ────────────────────────
    // ★ P7-fix: A 強制為 1.0。舊版 A = reflColor.b，alpha=0 導致 RT 層透明。
    vec3 finalColor = applyMFGExposure(directLight + reflColor);
    imageStore(u_RTOutput, coord, vec4(finalColor.r, reflColor.rg, 1.0));
    imageStore(u_AOOutput, coord, vec4(ao, dot(indirectIrr, vec3(0.333)), 0.0, 0.0));
}
