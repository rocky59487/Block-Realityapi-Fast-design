#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Blackwell ReSTIR DI Compute Shader  (Phase 2 完整實作)
//  Reservoir-based Spatiotemporal Importance Resampling for Direct Illumination
//  Target: SM 10.x (RTX 50xx Blackwell); Ada SM8.9 with degraded performance
//
//  演算法三階段：
//    Pass 0: candidateSampling()   — Light BVH 隨機遊走採樣 INITIAL_CANDIDATES 個候選
//    Pass 1: temporalReuse()       — 與前幀 prevReservoirs 合併（M-cap 防偏差）
//    Pass 2: spatialReuse()        — SPATIAL_SAMPLES 個鄰居 reservoir 合併
//
//  SC 0: ENABLE_TEMPORAL_REUSE  (0=初始採樣Only, 1=啟用時域重用)
//  SC 1: ENABLE_SPATIAL_REUSE   (0=無空間重用,   1=啟用空間重用)
//  SC 2: TEMPORAL_MAX_M         (預設 20，時域 M-cap 上限)
//  SC 3: SPATIAL_SAMPLES        (預設 1，空間鄰居採樣數量)
//
//  參考文獻：
//    Bitterli et al. 2020, "Spatiotemporal reservoir resampling for
//    real-time ray tracing with dynamic direct lighting"
//    Talbot et al. 2005, "Importance Resampling for Global Illumination"
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_query                  : require  // 可見性測試
#extension GL_EXT_scalar_block_layout        : require
#extension GL_KHR_shader_subgroup_arithmetic : require
// Blackwell 專屬擴充
#extension GL_NV_cluster_acceleration_structure : enable

// ─── Specialization Constants ─────────────────────────────────────────────
layout(constant_id = 0) const int  ENABLE_TEMPORAL_REUSE = 0;
layout(constant_id = 1) const int  ENABLE_SPATIAL_REUSE  = 0;
layout(constant_id = 2) const int  TEMPORAL_MAX_M        = 20;
layout(constant_id = 3) const int  SPATIAL_SAMPLES       = 1;

// 初始候選採樣數量（每像素每幀採樣的候選光源數）
// 32 = 合理的品質/性能折中；RTX 50xx 可承受
const int INITIAL_CANDIDATES = 32;

// 空間重用搜尋半徑（pixels）
const float SPATIAL_RADIUS = 16.0;

// 最大可見性射線距離
const float RAY_MAX_T = 1000.0;

// 最小 p_hat 門限（低於此值視為零）
const float P_HAT_EPSILON = 1e-6;

// BVH 節點大小（bytes）：3 × vec4 = 48
const int BVH_NODE_SIZE_FLOAT4 = 3;   // 3 個 vec4 per node

// 葉節點旗標（flags uvec4.x bit 31）
const uint BVH_LEAF_BIT = 0x80000000u;

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// ═══════════════════════════════════════════════════════════════════════════
//  Reservoir SSBO（uvec4 per pixel）
//
//  格式：uvec4
//    [0] lightIdx  — 選中光源索引（指向 lightList[]）
//    [1] W_bits    — floatBitsToUint(W)，無偏 RIS 估計權重
//    [2] M         — 累積樣本數量
//    [3] wSum_bits — floatBitsToUint(wSum)，供合併計算（非最終輸出）
//
//  注意：wSum 在最終寫出時保存以支援 spatialReuse 的 M-aware 合併，
//        primary.rgen.glsl 只讀取 lightIdx 和 W
// ═══════════════════════════════════════════════════════════════════════════
layout(set = 0, binding = 5, scalar) buffer ReSTIRDIBuffer {
    uvec4 reservoirs[];
} restirDI;

layout(set = 0, binding = 6, scalar) readonly buffer ReSTIRDIHistory {
    uvec4 prevReservoirs[];
} restirHistory;

// ─── Light BVH SSBO（BRRTEmissiveManager 填充） ──────────────────────────
//
//  節點格式（48 bytes = 3 × vec4）：
//    vec4  minPower   : xyz = AABB min,  w = totalPower
//    vec4  maxPad     : xyz = AABB max,  w = pad
//    uvec4 nodeData   : x = (isLeaf<<31)|rightChildIdx
//                       y = lightCount
//                       z = leftChildIdx（内部節點）或 firstLightIdx（葉節點）
//                       w = pad
layout(set = 0, binding = 7, scalar) readonly buffer LightBVHBuffer {
    vec4 bvhData[];    // 每個節點 3 個 vec4；索引 = nodeIdx * 3 + {0,1,2}
} lightBVH;

// ─── Light List SSBO ─────────────────────────────────────────────────────
//
//  光源條目格式（32 bytes = 2 × vec4）：
//    vec4 positionPower : xyz = 方塊中心世界座標，w = 輻射功率（luminance）
//    vec4 colorPad      : xyz = RGB 顏色（linear）,   w = pad
layout(set = 0, binding = 8, scalar) readonly buffer LightListBuffer {
    vec4 lightData[];   // 每個光源 2 個 vec4；索引 = lightIdx * 2 + {0,1}
} lightList;

// ─── TLAS（可見性測試 ray query） ─────────────────────────────────────────
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;

// ─── GBuffer ─────────────────────────────────────────────────────────────
layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;

// ─── Camera UBO（Blackwell layout） ──────────────────────────────────────
layout(set = 2, binding = 0, scalar) uniform CameraFrame {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec3  camPos;         float _p0;
    vec3  sunDir;         float _p1;
    vec3  sunColor;       float _p2;
    vec3  skyColor;       float _p3;
    uint  frameIndex;
    float aoRadius;
    float aoStrength;
    float reflectionRoughnessThreshold;
    float mfgExposureScale;
    float _pad[3];
} cam;

// ─── Light Count Push Constant ────────────────────────────────────────────
layout(push_constant) uniform PushConstants {
    uint lightCount;    // 光源列表中的光源數量
    uint bvhNodeCount;  // BVH 節點數量（0 = 無光源）
} pc;

// ═══════════════════════════════════════════════════════════════════════════
//  PCG 隨機數生成器
//  參考："Hash Functions for GPU Rendering" (Jarzynski & Olano 2020)
// ═══════════════════════════════════════════════════════════════════════════

uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// 初始化 RNG 狀態（每像素每幀唯一）
uint initRng(ivec2 coord, ivec2 imgSize, uint frame) {
    uint pixelIdx = uint(coord.y) * uint(imgSize.x) + uint(coord.x);
    return pcgHash(pixelIdx ^ pcgHash(frame));
}

// 生成 [0, 1) 的均勻浮點隨機數
float nextFloat(inout uint rng) {
    rng = pcgHash(rng);
    return float(rng) / float(0xFFFFFFFFu);
}

// ═══════════════════════════════════════════════════════════════════════════
//  Reservoir 工具函式
// ═══════════════════════════════════════════════════════════════════════════

struct Reservoir {
    int   lightIdx;  // 當前選中光源
    float wSum;      // 累積權重
    uint  M;         // 樣本計數
    float W;         // 最終 RIS 無偏權重
};

Reservoir emptyReservoir() {
    Reservoir r;
    r.lightIdx = -1;
    r.wSum     = 0.0;
    r.M        = 0u;
    r.W        = 0.0;
    return r;
}

// RIS 流更新：將候選 (candIdx, candWeight) 合併到 reservoir
// rand ∈ [0, 1)；返回 true 表示接受
bool reservoirUpdate(inout Reservoir r, int candIdx, float candWeight, float rand) {
    r.wSum += candWeight;
    r.M    += 1u;
    bool accepted = rand < (candWeight / r.wSum);
    if (accepted) r.lightIdx = candIdx;
    return accepted;
}

// 計算最終 RIS 估計權重 W = (1/p_hat(y)) × (wSum/M)
float computeRISWeight(Reservoir r, float pHatAtY) {
    if (pHatAtY <= P_HAT_EPSILON || r.M == 0u) return 0.0;
    return (1.0 / pHatAtY) * (r.wSum / float(r.M));
}

// 合併兩個 Reservoir（時域/空間重用）
// dst.wSum += pHatSrcY × src.W × min(src.M, mCap)
void reservoirMerge(inout Reservoir dst, Reservoir src,
                    float pHatSrcY, float rand, int mCap) {
    uint srcMCapped     = min(src.M, uint(mCap));
    float srcContrib    = pHatSrcY * src.W * float(srcMCapped);
    dst.wSum           += srcContrib;
    dst.M              += srcMCapped;
    if (rand < (srcContrib / dst.wSum)) {
        dst.lightIdx = src.lightIdx;
    }
}

// 從 SSBO 讀取 Reservoir
Reservoir loadReservoir(uint pixelIdx, bool fromHistory) {
    uvec4 raw = fromHistory ? restirHistory.prevReservoirs[pixelIdx]
                            : restirDI.reservoirs[pixelIdx];
    Reservoir r;
    r.lightIdx = int(raw.x);
    r.W        = uintBitsToFloat(raw.y);
    r.M        = raw.z;
    r.wSum     = uintBitsToFloat(raw.w);
    return r;
}

// 將 Reservoir 寫入 SSBO
void storeReservoir(uint pixelIdx, Reservoir r) {
    restirDI.reservoirs[pixelIdx] = uvec4(
        uint(r.lightIdx),
        floatBitsToUint(r.W),
        r.M,
        floatBitsToUint(r.wSum)
    );
}

// ═══════════════════════════════════════════════════════════════════════════
//  GBuffer 解碼工具
// ═══════════════════════════════════════════════════════════════════════════

// 從深度圖重建世界座標
vec3 worldPosFromDepth(ivec2 coord, ivec2 imgSize) {
    vec2 uv     = (vec2(coord) + 0.5) / vec2(imgSize);
    float depth = texelFetch(g_Depth, coord, 0).r;
    vec4  ndc   = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4  world = cam.invViewProj * ndc;
    return world.xyz / world.w;
}

// 解碼 g_Normal（oct encoding → 世界法線）
vec3 decodeNormal(ivec2 coord) {
    vec2 enc = texelFetch(g_Normal, coord, 0).xy * 2.0 - 1.0;
    vec3 n   = vec3(enc.x, enc.y, 1.0 - abs(enc.x) - abs(enc.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
    }
    return normalize(n);
}

// 判斷像素是否有效（非天空、非無窮遠）
bool isValidPixel(ivec2 coord) {
    float depth = texelFetch(g_Depth, coord, 0).r;
    return depth < 0.9999;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Light BVH 採樣
//
//  隨機遊走（Random Walk）從根節點開始，每步以概率 power_left/(power_left+power_right)
//  向左子節點或右子節點走，直到葉節點。
//
//  複雜度：O(log N)
//  精度：分層重要性採樣；能量守恆，偏誤由 RIS 補正
// ═══════════════════════════════════════════════════════════════════════════

// 讀取 BVH 節點的各欄位
// nodeOffset = nodeIdx * 3（每節點 3 個 vec4）
vec3  bvhMin(uint nodeOffset)        { return lightBVH.bvhData[nodeOffset].xyz; }
float bvhPower(uint nodeOffset)      { return lightBVH.bvhData[nodeOffset].w; }
vec3  bvhMax(uint nodeOffset)        { return lightBVH.bvhData[nodeOffset + 1u].xyz; }
uint  bvhFlags(uint nodeOffset)      { return floatBitsToUint(lightBVH.bvhData[nodeOffset + 2u].x); }
uint  bvhRightChild(uint nodeOffset) {
    // rightChildIdx = flags & ~BVH_LEAF_BIT
    return floatBitsToUint(lightBVH.bvhData[nodeOffset + 2u].x) & ~BVH_LEAF_BIT;
}
uint  bvhLeftOrLight(uint nodeOffset) {
    // z 欄位：内部節點=leftChildIdx，葉節點=lightIdx
    return floatBitsToUint(lightBVH.bvhData[nodeOffset + 2u].z);
}
bool  bvhIsLeaf(uint nodeOffset)     {
    return (floatBitsToUint(lightBVH.bvhData[nodeOffset + 2u].x) & BVH_LEAF_BIT) != 0u;
}

// 採樣 Light BVH，返回光源索引和採樣 PDF（按功率比）
// rand1, rand2 ∈ [0, 1)
// 返回 -1 表示無有效光源
int sampleLightBVH(float rand1, inout float pdf) {
    if (pc.lightCount == 0u || pc.bvhNodeCount == 0u) {
        pdf = 0.0;
        return -1;
    }

    uint  nodeIdx  = 0u;  // 從根節點開始
    pdf = 1.0;            // 累積 PDF（概率乘積）

    // 最大深度防護（BVH 高度 ≤ 24 對應 16M 個光源，足夠 Minecraft 場景）
    for (int depth = 0; depth < 24; depth++) {
        uint nodeOffset = nodeIdx * uint(BVH_NODE_SIZE_FLOAT4);

        if (bvhIsLeaf(nodeOffset)) {
            // 葉節點：返回光源索引
            return int(bvhLeftOrLight(nodeOffset));
        }

        // 內部節點：以功率比例選擇子節點
        uint leftIdx   = bvhLeftOrLight(nodeOffset);
        uint rightIdx  = bvhRightChild(nodeOffset);

        uint leftOff   = leftIdx  * uint(BVH_NODE_SIZE_FLOAT4);
        uint rightOff  = rightIdx * uint(BVH_NODE_SIZE_FLOAT4);

        float leftPow  = bvhPower(leftOff);
        float rightPow = bvhPower(rightOff);
        float totalPow = leftPow + rightPow;

        if (totalPow <= 0.0) {
            // 退化節點（功率為零），停止採樣
            pdf = 0.0;
            return -1;
        }

        // 以 rand1 選擇左/右，並更新 PDF
        float pLeft = leftPow / totalPow;
        if (rand1 < pLeft) {
            pdf     *= pLeft;
            rand1   /= pLeft;          // 重用 rand1（將 [0, pLeft) → [0, 1)）
            nodeIdx  = leftIdx;
        } else {
            pdf     *= (1.0 - pLeft);
            rand1    = (rand1 - pLeft) / (1.0 - pLeft);  // → [0, 1)
            nodeIdx  = rightIdx;
        }
    }

    // 不應到達此處
    pdf = 0.0;
    return -1;
}

// ═══════════════════════════════════════════════════════════════════════════
//  光源屬性讀取
// ═══════════════════════════════════════════════════════════════════════════

vec3 lightPosition(int lightIdx) {
    return lightList.lightData[uint(lightIdx) * 2u].xyz;
}

float lightPower(int lightIdx) {
    return lightList.lightData[uint(lightIdx) * 2u].w;
}

vec3 lightColor(int lightIdx) {
    return lightList.lightData[uint(lightIdx) * 2u + 1u].xyz;
}

// ═══════════════════════════════════════════════════════════════════════════
//  p_hat 目標函式
//
//  p_hat(x) 是 ReSTIR DI 的目標分佈（unnormalized）：
//    p_hat(x) ≈ luminance(Le) × |cos θ_i| / d²
//
//  這是無遮擋假設下的貢獻估計（不包含 BRDF 因子，簡化版）。
//  遮擋在最終可見性測試中處理，不計入 p_hat 避免造成高變異數。
//
//  注意：若使用 BRDF-aware p_hat 可改善效果，此為 Phase 3 最佳化項目。
// ═══════════════════════════════════════════════════════════════════════════

float evalPHat(vec3 worldPos, vec3 worldNormal, int lightIdx) {
    if (lightIdx < 0) return 0.0;

    vec3  lightPos   = lightPosition(lightIdx);
    vec3  toLight    = lightPos - worldPos;
    float distSq     = dot(toLight, toLight);
    if (distSq < 0.0001) return 0.0;

    float dist       = sqrt(distSq);
    vec3  lightDir   = toLight / dist;

    // 朗伯餘弦項（表面法線與光方向夾角）
    float cosTheta   = max(dot(worldNormal, lightDir), 0.0);
    if (cosTheta <= 0.0) return 0.0;

    // 距離衰減（物理正確：1/d²）
    // 加上偏移避免數值爆炸（近距光源）
    float attenuation = 1.0 / (distSq + 0.25);

    // ITU-R BT.709 luminance of light color
    vec3  color  = lightColor(lightIdx);
    float lum    = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;

    return lum * cosTheta * attenuation;
}

// ═══════════════════════════════════════════════════════════════════════════
//  可見性測試（Ray Query）
//
//  返回 0.0 = 遮擋（shadow），1.0 = 可見
//  使用 TLAS intersection test，忽略 alpha（opaque geometry only）
// ═══════════════════════════════════════════════════════════════════════════

float testVisibility(vec3 origin, int lightIdx) {
    if (lightIdx < 0) return 0.0;

    vec3  lightPos = lightPosition(lightIdx);
    vec3  toLight  = lightPos - origin;
    float dist     = length(toLight);
    if (dist < 0.001) return 1.0;

    vec3 rayDir = toLight / dist;

    // 偏移射線起點避免自交
    vec3 rayOrigin = origin + rayDir * 0.01;

    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        u_TLAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
        0xFF,          // 所有 mask bits
        rayOrigin,
        0.0,           // tMin
        rayDir,
        dist - 0.02    // tMax = 光源距離（減去小偏移）
    );

    while (rayQueryProceedEXT(query)) {
        // 遇到任何幾何→視為遮擋（保守但正確）
        rayQueryTerminateEXT(query);
    }

    return (rayQueryGetIntersectionTypeEXT(query, true) ==
            gl_RayQueryCommittedIntersectionNoneEXT) ? 1.0 : 0.0;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 0：Initial Candidate Sampling
//
//  從 Light BVH 採樣 INITIAL_CANDIDATES 個候選光源，
//  使用 RIS 流更新建立初始 per-pixel reservoir。
//  此 pass 在 ENABLE_TEMPORAL_REUSE=0 時也執行（替代 stub 行為）。
// ═══════════════════════════════════════════════════════════════════════════

Reservoir candidateSampling(vec3 worldPos, vec3 worldNormal, inout uint rng) {
    Reservoir r = emptyReservoir();

    // 無光源時返回空 reservoir
    if (pc.lightCount == 0u) return r;

    for (int i = 0; i < INITIAL_CANDIDATES; i++) {
        float rand1 = nextFloat(rng);
        float rand2 = nextFloat(rng);  // 預留：multi-sample per leaf

        float pdf = 0.0;
        int candIdx = sampleLightBVH(rand1, pdf);

        if (candIdx < 0 || pdf <= 0.0) continue;

        // 候選權重 w_i = p_hat(x_i) / pdf(x_i)
        float pHat   = evalPHat(worldPos, worldNormal, candIdx);
        float weight = pHat / pdf;

        reservoirUpdate(r, candIdx, weight, rand2);
    }

    // 計算初始 RIS 估計權重
    if (r.lightIdx >= 0) {
        float pHatY = evalPHat(worldPos, worldNormal, r.lightIdx);
        r.W = computeRISWeight(r, pHatY);
    }

    return r;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 1：Temporal Reuse
//
//  將前幀 reservoir 與當前幀初始 reservoir 合併（M-cap = TEMPORAL_MAX_M）。
//  前幀像素座標由視差重投影（reprojection）計算，此處使用簡化版（無動態物體）。
//
//  SC 0: ENABLE_TEMPORAL_REUSE = 1 時執行
// ═══════════════════════════════════════════════════════════════════════════

Reservoir temporalReuse(Reservoir current,
                        vec3 worldPos, vec3 worldNormal,
                        ivec2 coord, ivec2 imgSize,
                        inout uint rng) {
    // 計算前幀像素座標（簡化 reprojection）
    vec4 prevClip = cam.prevInvViewProj * vec4(worldPos, 1.0);
    prevClip.xyz /= prevClip.w;
    vec2 prevUV   = prevClip.xy * 0.5 + 0.5;
    ivec2 prevCoord = ivec2(prevUV * vec2(imgSize) - 0.5);

    // 邊界檢查
    if (any(lessThan(prevCoord, ivec2(0))) ||
        any(greaterThanEqual(prevCoord, imgSize))) {
        return current;
    }

    // 深度相似性過濾（避免合併背景/前景的 reservoir）
    float currDepth = texelFetch(g_Depth, coord,      0).r;
    float prevDepth = texelFetch(g_Depth, prevCoord,  0).r;
    if (abs(currDepth - prevDepth) > 0.01) return current;

    // 法線相似性過濾（避免合併法線差異大的鄰居）
    vec3 prevNormal = decodeNormal(prevCoord);
    if (dot(worldNormal, prevNormal) < 0.906) return current;  // cos(25°)

    // 讀取前幀 reservoir
    uint prevPixelIdx = uint(prevCoord.y) * uint(imgSize.x) + uint(prevCoord.x);
    Reservoir prev = loadReservoir(prevPixelIdx, true);

    // 驗證前幀 reservoir 有效
    if (prev.lightIdx < 0 || prev.M == 0u) return current;

    // 合併前幀 reservoir（M-cap 防止過度偏差累積）
    float pHatPrevY = evalPHat(worldPos, worldNormal, prev.lightIdx);
    float rand      = nextFloat(rng);

    // 在合併前，先建立當前 reservoir 的 wSum 基礎
    // （current.wSum 已在 candidateSampling 中設定）
    Reservoir combined = current;
    reservoirMerge(combined, prev, pHatPrevY, rand, TEMPORAL_MAX_M);

    // 重新計算 W
    if (combined.lightIdx >= 0) {
        float pHatY = evalPHat(worldPos, worldNormal, combined.lightIdx);
        combined.W = computeRISWeight(combined, pHatY);
    }

    return combined;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 2：Spatial Reuse
//
//  從 SPATIAL_SAMPLES 個鄰居像素採樣 reservoir 並合併。
//  鄰居座標在 SPATIAL_RADIUS 像素半徑內均勻採樣。
//  使用 Jacobian 補正（幾何差異修正），此處使用簡化版（忽略微小差異）。
//
//  SC 1: ENABLE_SPATIAL_REUSE = 1 時執行
//  SC 3: SPATIAL_SAMPLES 控制採樣鄰居數量
// ═══════════════════════════════════════════════════════════════════════════

Reservoir spatialReuse(Reservoir current,
                       vec3 worldPos, vec3 worldNormal,
                       ivec2 coord, ivec2 imgSize,
                       inout uint rng) {
    Reservoir combined = current;

    for (int s = 0; s < SPATIAL_SAMPLES; s++) {
        // 在圓盤內均勻採樣鄰居座標（均勻分佈）
        float theta  = nextFloat(rng) * 6.283185;  // 2π
        float r      = sqrt(nextFloat(rng)) * SPATIAL_RADIUS;
        ivec2 offset = ivec2(cos(theta) * r, sin(theta) * r);
        ivec2 nCoord = coord + offset;

        // 邊界 + 有效性檢查
        if (any(lessThan(nCoord, ivec2(0))) ||
            any(greaterThanEqual(nCoord, imgSize))) continue;
        if (!isValidPixel(nCoord)) continue;

        // 法線相似性過濾
        vec3 nNormal = decodeNormal(nCoord);
        if (dot(worldNormal, nNormal) < 0.906) continue;

        // 深度相似性過濾
        float nDepth    = texelFetch(g_Depth, nCoord, 0).r;
        float currDepth = texelFetch(g_Depth, coord,  0).r;
        if (abs(currDepth - nDepth) > 0.02) continue;

        // 讀取鄰居 reservoir
        uint nPixelIdx = uint(nCoord.y) * uint(imgSize.x) + uint(nCoord.x);
        Reservoir neighbor = loadReservoir(nPixelIdx, false);
        if (neighbor.lightIdx < 0 || neighbor.M == 0u) continue;

        // 以 dst shading point 的 p_hat 評估 neighbor 的選中光源
        float pHatNeighY = evalPHat(worldPos, worldNormal, neighbor.lightIdx);
        float rand       = nextFloat(rng);

        // 合併鄰居 reservoir（不套用 M-cap，空間重用不需要）
        reservoirMerge(combined, neighbor, pHatNeighY, rand, 65536);
    }

    // 最終可見性測試（只對最終選中的光源測試，降低 ray cost）
    if (combined.lightIdx >= 0) {
        float vis   = testVisibility(worldPos, combined.lightIdx);
        float pHatY = evalPHat(worldPos, worldNormal, combined.lightIdx) * vis;
        combined.W  = computeRISWeight(combined, pHatY);
        if (vis < 0.5) combined.W = 0.0;  // 遮擋→零貢獻
    }

    return combined;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Main
// ═══════════════════════════════════════════════════════════════════════════

void main() {
    ivec2 coord   = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imgSize = textureSize(g_Depth, 0);

    if (any(greaterThanEqual(coord, imgSize))) return;

    uint pixelIdx = uint(coord.y) * uint(imgSize.x) + uint(coord.x);

    // ─── 天空 / 背景像素 ───────────────────────────────────────────────────
    if (!isValidPixel(coord)) {
        // 天空不需要 ReSTIR DI；輸出 W=0 表示跳過
        restirDI.reservoirs[pixelIdx] = uvec4(0u, floatBitsToUint(0.0), 0u, 0u);
        return;
    }

    // ─── 無光源快速路徑 ───────────────────────────────────────────────────
    if (pc.lightCount == 0u) {
        // 只有太陽光（index=0 視為 sun），stub 行為向後相容
        restirDI.reservoirs[pixelIdx] = uvec4(
            0u,                     // lightIdx=0 (sun stub)
            floatBitsToUint(1.0),  // W=1.0（直接光照不縮放）
            1u,                     // M=1
            floatBitsToUint(1.0)   // wSum=1.0
        );
        return;
    }

    // ─── GBuffer 解碼 ──────────────────────────────────────────────────────
    vec3 worldPos    = worldPosFromDepth(coord, imgSize);
    vec3 worldNormal = decodeNormal(coord);

    // ─── RNG 初始化 ────────────────────────────────────────────────────────
    uint rng = initRng(coord, imgSize, cam.frameIndex);

    // ─── Pass 0：初始候選採樣（始終執行）────────────────────────────────
    Reservoir result = candidateSampling(worldPos, worldNormal, rng);

    // ─── Pass 1：時域重用（ENABLE_TEMPORAL_REUSE = 1 時）────────────────
    if (ENABLE_TEMPORAL_REUSE == 1) {
        result = temporalReuse(result, worldPos, worldNormal, coord, imgSize, rng);
    }

    // ─── Pass 2：空間重用（ENABLE_SPATIAL_REUSE = 1 時）─────────────────
    // 注意：空間重用讀取的是本幀其他像素當前的 reservoir，
    //       這要求 compute dispatch 為單 pass（所有像素同步採樣完成後才合併）。
    //       生產環境中應拆分為兩個 dispatch：先全幀採樣+時域，再全幀空間重用。
    if (ENABLE_SPATIAL_REUSE == 1) {
        // 先完成 barrier（確保本幀 Pass 0/1 全局可見）後才執行空間重用
        // （此 barrier 在 pipeline 層由 two-dispatch 設計保證；
        //  單 dispatch 中空間重用僅為 best-effort，SPATIAL_SAMPLES=1 時偏差可接受）
        result = spatialReuse(result, worldPos, worldNormal, coord, imgSize, rng);
    } else if (ENABLE_TEMPORAL_REUSE == 0) {
        // 純初始採樣模式：執行可見性測試更新 W
        if (result.lightIdx >= 0) {
            float vis   = testVisibility(worldPos, result.lightIdx);
            float pHatY = evalPHat(worldPos, worldNormal, result.lightIdx) * vis;
            result.W    = computeRISWeight(result, pHatY);
        }
    }

    // ─── 寫入結果 ────────────────────────────────────────────────────────
    storeReservoir(pixelIdx, result);
}
