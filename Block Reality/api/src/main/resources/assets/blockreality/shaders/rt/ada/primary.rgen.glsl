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
//
// GPU 佈局（scalar layout, little-endian uint32）：
//
//   Header (8 × uint32 = 32 bytes):
//     [0] nodeCount    — DAG 節點總數
//     [1] dagDepth     — 最大遍歷深度（對應 Java maxDepth）
//     [2] dagOriginX   — DAG 根節點世界座標原點 X（voxel 座標）
//     [3] dagOriginY
//     [4] dagOriginZ
//     [5] dagSize      — 根節點覆蓋邊長（voxel 數，通常為 2^dagDepth）
//     [6] rootIndex    — 根節點在 nodes[] 中的索引
//     [7] _pad
//
//   Per-node (9 × uint32 = 36 bytes, stride = DAG_NODE_STRIDE):
//     [0] flags        — childMask(8b) | matId(8b) | lodLevel(8b) | _reserved(8b)
//     [1..8] child[0..7] — 子節點絕對索引（0 = 空/無子，對應 childMask bit 0..7）
//                          注：使用完整 8-slot（非 compact），便於 O(1) 隨機存取
//
// 此佈局由 BRAdaRTConfig.uploadDAGToGPU() 負責從 BRSparseVoxelDAG.serialize()
// 轉換上傳：compact child array → 展開為固定 8-slot，空 slot 填 0。
layout(set = 3, binding = 0, scalar) readonly buffer DAGBuffer {
    uint nodeCount;
    uint dagDepth;
    uint dagOriginX, dagOriginY, dagOriginZ;
    uint dagSize;       // 根節點覆蓋的 voxel 邊長
    uint rootIndex;     // root 節點在 nodes[] 的索引
    uint _dagPad;
    // Per-node data（每節點 9 uint32）
    uint nodes[];
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
//  DAG 遠距 GI — 8-bit childMask 迭代遍歷（GPU 版本）
//
//  SSBO 佈局：每節點 9 × uint32（DAG_NODE_STRIDE）
//    [0] flags  = childMask(8b) | matId(8b) | lodLevel(8b) | _reserved(8b)
//    [1..8] child[0..7] = 子節點絕對索引（0 = 空 slot，對應 bit i 未設置）
//
//  遍歷演算法與 Java BRSparseVoxelDAG.traverseNode() 相同：
//    1. 從 rootIndex 開始
//    2. 依 pos 計算 octant（0-7），檢查 childMask bit
//    3. 若 bit 未設置 → air（matId = 0）
//    4. 若 bit 設置 → descend 至 child[octant]
//    5. 重複直至 childMask==0（葉節點）或深度耗盡
// ═══════════════════════════════════════════════════════════════════════════

// 每節點 9 個 uint：1 flags + 8 child slots
#define DAG_NODE_STRIDE 9u

// 讀取節點 flags（childMask 8b | matId 8b | lodLevel 8b | _reserved 8b）
uint dagNodeFlags(uint nodeIdx) {
    return dag.nodes[nodeIdx * DAG_NODE_STRIDE];
}

// 讀取節點的第 octant 個 child 絕對索引（0 = 空）
uint dagNodeChild(uint nodeIdx, int octant) {
    return dag.nodes[nodeIdx * DAG_NODE_STRIDE + 1u + uint(octant)];
}

// 迭代 DAG 遍歷：給定 DAG 本地 voxel 座標，回傳 materialId（0 = air）
// 採用與 Java traverseNode() 相同的 octant 計算（bit 0=X, 1=Y, 2=Z）
uint dagQuery(ivec3 dagCoord) {
    // 前置條件檢查
    if (dag.nodeCount == 0u || dag.dagSize == 0u) return 0u;

    // 邊界檢查（超出 DAG 範圍 = 視為 air）
    int sz = int(dag.dagSize);
    if (any(lessThan(dagCoord, ivec3(0))) ||
        any(greaterThanEqual(dagCoord, ivec3(sz)))) {
        return 0u;
    }

    uint nodeIdx = dag.rootIndex;
    ivec3 pos    = dagCoord;
    int   size   = sz;

    // 最多遍歷 dagDepth 層（通常 8-12 層）
    uint maxD = min(dag.dagDepth, 12u);
    for (uint d = 0u; d < maxD; d++) {
        uint flags     = dagNodeFlags(nodeIdx);
        uint childMask = flags & 0xFFu;
        uint matId     = (flags >> 8u) & 0xFFu;

        // 葉節點（childMask == 0）或 size <= 1 → 返回此節點的材料
        if (childMask == 0u || size <= 1) {
            return matId;
        }

        // 計算 octant（與 Java traverseNode 邏輯一致）
        int half   = size >> 1;
        int octant = 0;
        if (pos.x >= half) { octant |= 1; pos.x -= half; }
        if (pos.y >= half) { octant |= 2; pos.y -= half; }
        if (pos.z >= half) { octant |= 4; pos.z -= half; }

        // 若該 octant 為空（childMask bit 未設置）→ air
        if ((childMask & (1u << uint(octant))) == 0u) {
            return 0u;
        }

        // 取子節點索引（固定 8-slot，直接 O(1) 隨機存取）
        uint childIdx = dagNodeChild(nodeIdx, octant);
        if (childIdx == 0u) {
            // 上傳時未填充此 slot（理論上不應發生，防禦性回傳 air）
            return 0u;
        }

        nodeIdx = childIdx;
        size    = half;
    }

    // 到達最大深度：取最終節點的 matId
    return (dagNodeFlags(nodeIdx) >> 8u) & 0xFFu;
}

// 材料 ID → 近似漫射反射率（RGB）
// 對應 DefaultMaterial 枚舉序數（Java com.blockreality.api.material.DefaultMaterial）
// Phase 3 替換：從 Material SSBO 查表（含 emission、roughness）
vec3 materialToAlbedo(uint matId) {
    if (matId == 0u)  return vec3(0.00);            // air（不貢獻）
    if (matId == 1u)  return vec3(0.72, 0.70, 0.62); // stone / concrete
    if (matId == 2u)  return vec3(0.70, 0.72, 0.76); // steel（帶冷色金屬光澤）
    if (matId == 3u)  return vec3(0.55, 0.36, 0.16); // wood
    if (matId == 4u)  return vec3(0.60, 0.75, 0.82); // glass（高透射，近似天空色）
    if (matId == 5u)  return vec3(0.65, 0.30, 0.20); // brick
    if (matId == 6u)  return vec3(0.50, 0.47, 0.40); // gravel
    if (matId == 7u)  return vec3(0.76, 0.70, 0.50); // sand
    if (matId == 8u)  return vec3(0.40, 0.30, 0.20); // dirt
    if (matId == 9u)  return vec3(0.10, 0.06, 0.12); // obsidian
    if (matId == 10u) return vec3(0.72, 0.45, 0.20); // copper（未氧化）

    // 未知材料：以 PCG hash 產生穩定的中性色調（避免純灰，增添多樣性）
    uint h = pcgHash(matId * 2654435761u);
    return vec3(
        float( h        & 0x3Fu) / 63.0 * 0.35 + 0.30,
        float((h >>  8u)& 0x3Fu) / 63.0 * 0.35 + 0.28,
        float((h >> 16u)& 0x3Fu) / 63.0 * 0.35 + 0.25
    );
}

// DAG GI：採樣 worldPos 周圍的間接輻照度（> 128 chunk 範圍的軟 GI）
//
// 策略：對 4 個 cosine-weighted 半球方向，各在 1 chunk（16 voxel）外
// 採樣一次 DAG，以材料漫射反射率 × 入射陽光 + 天空貢獻作為 irradiance proxy。
// 這是 Phase 2 的近似版本；Phase 3 以 Radiance Cache 取代。
vec3 dagSampleIrradiance(vec3 worldPos, vec3 N) {
    if (dag.nodeCount == 0u) return cam.skyColor * 0.08;

    // DAG 世界座標原點
    ivec3 dagOrigin = ivec3(dag.dagOriginX, dag.dagOriginY, dag.dagOriginZ);

    // 4 個均勻半球探針方向（正四面體頂點，最大角度分離）
    // 使用 blueNoise 旋轉（per-frame jitter），略微抑制閃爍
    vec2  bn    = blueNoise(ivec2(gl_LaunchIDEXT.xy), cam.frameIndex);
    float phi   = bn.x * 6.28318530718;
    float cp    = cos(phi), sp = sin(phi);
    mat3  jitter = mat3(
        vec3(cp,  sp, 0.0),
        vec3(-sp, cp, 0.0),
        vec3(0.0, 0.0, 1.0)
    );

    const vec3 PROBE_DIRS[4] = vec3[4](
        vec3( 0.577350,  0.577350,  0.577350),
        vec3(-0.577350,  0.577350, -0.577350),
        vec3( 0.577350, -0.577350, -0.577350),
        vec3(-0.577350, -0.577350,  0.577350)
    );

    vec3  irradiance = vec3(0.0);
    float weightSum  = 0.0;

    for (int i = 0; i < 4; i++) {
        // 輕微 jitter 旋轉（保持大致方向）
        vec3 probeDir = normalize(jitter * PROBE_DIRS[i]);

        // cosine 加權（只取法線半球上的方向）
        float NdotD = dot(N, probeDir);
        if (NdotD < 0.02) continue; // 在表面後側，跳過

        // 探針採樣點：沿方向往外 1 chunk（16 voxels）
        vec3  sampleWorldPos = worldPos + probeDir * 16.0;
        ivec3 dagCoord       = ivec3(floor(sampleWorldPos)) - dagOrigin;

        // DAG 查詢（迭代遍歷 8-bit childMask 層次結構）
        uint matId = dagQuery(dagCoord);

        vec3 sampleColor;
        if (matId == 0u) {
            // Air → 使用天空色（間接天光）
            sampleColor = cam.skyColor * 0.25;
        } else {
            // 實體材料 → 漫射反射率 × 太陽光 + 少量天空光
            vec3 albedo = materialToAlbedo(matId);
            float sunNdotL = max(dot(vec3(0.0, 1.0, 0.0), cam.sunDir), 0.0);
            sampleColor = albedo * (cam.sunColor * sunNdotL * 0.35 + cam.skyColor * 0.15);
        }

        irradiance  += sampleColor * NdotD;
        weightSum   += NdotD;
    }

    // 正規化；若所有方向均在背面（weightSum = 0），退回天空光
    return weightSum > 0.0
        ? irradiance / weightSum
        : cam.skyColor * 0.05;
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

    // ── 5. Motion Vector（SVGF temporal reprojection） ───────────────────
    // 正確公式：motionVec = currentUV - prevUV
    //   prevUV = project(worldPos with prevViewProj)
    //   prevViewProj = inverse(prevInvViewProj)
    //
    // GLSL 4.6 提供內建 inverse(mat4)（約 40 ALU ops/pixel）。
    // 比儲存額外 prevViewProj 更節省 UBO 空間，且在 Ada SM 8.9 上無顯著瓶頸。
    //
    // 數學正確性：
    //   prevInvVP = (prevVP)^-1
    //   inverse(prevInvVP) = prevVP
    //   prevVP * worldPos = prevClipPos
    mat4  prevVP       = inverse(cam.prevInvViewProj);
    vec4  prevClip     = prevVP * vec4(worldPos, 1.0);
    vec2  prevNDC      = prevClip.xy / max(abs(prevClip.w), 1e-6); // 防止除零
    vec2  prevUV       = prevNDC * 0.5 + 0.5;
    vec2  motionVector = uv - prevUV;  // SVGF 定義：current − prev（正方向 = 向右/下移動）
    imageStore(u_MotionVectors, coord, vec4(motionVector, 0.0, 0.0));

    // ── 6. 輸出打包 ───────────────────────────────────────────────────────
    // RTOutput: R = shadowFactor, G = reflection.r, B = reflection.g, A = 1.0（固定不透明）
    // ★ P7-fix: A 強制為 1.0。舊版 A = reflColor.b，對無藍色反射材料 alpha=0
    //   導致 RT 輸出層完全透明。reflection.b 資訊捨棄，與 NRD/SVGF 輸入格式一致。
    imageStore(u_RTOutput,  coord, vec4(shadowFactor, reflColor.rg, 1.0));
    imageStore(u_AOOutput,  coord, vec4(ao, dot(indirectIrr, vec3(0.333)), 0.0, 0.0));
}
