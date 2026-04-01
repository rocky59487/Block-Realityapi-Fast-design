#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Blackwell ReSTIR GI Compute Shader  (Phase 3)
//  Reservoir-based Spatiotemporal Importance Resampling for Global Illumination
//  Target: SM 10.x (RTX 50xx Blackwell); Ada SM8.9 with SC GI_RAYS_PER_PIXEL=2
//
//  演算法：
//    Pass 0: 次射線採樣  — 每像素發射 GI_RAYS_PER_PIXEL 條餘弦加權次射線，
//                          以 SVDAG（BRSparseVoxelDAG）traversal 找到擊中點，
//                          讀取擊中點的 albedo / emission，再對擊中點發射 shadow ray
//                          確認有效性；RIS 流更新 GI reservoir
//    Pass 1: 時域重用    — 與前幀 GI reservoir 合併（M-cap = GI_MAX_M）
//    Pass 2: 空間重用    — GI_SPATIAL_SAMPLES 個鄰居 reservoir 合併
//    最終輸出            — 寫入 current GI SSBO，供 primary.rgen.glsl 讀取做
//                          漫反射 / 鏡面間接照明的加權貢獻
//
//  SC 0: GI_RAYS_PER_PIXEL     (Blackwell=4, Ada=2)
//  SC 1: ENABLE_GI_TEMPORAL    (0=Off, 1=On)
//  SC 2: ENABLE_GI_SPATIAL     (0=Off, 1=On)
//  SC 3: GI_MAX_M              (預設 10)
//  SC 4: GI_SPATIAL_SAMPLES    (預設 1)
//
//  參考文獻：
//    Ouyang et al. 2021, "ReSTIR GI: Path Resampling for Real-Time
//    Path Tracing", High Performance Graphics 2021
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_query                  : require  // 次射線 + 遮擋測試
#extension GL_EXT_scalar_block_layout        : require
#extension GL_KHR_shader_subgroup_arithmetic : require
#extension GL_NV_cluster_acceleration_structure : enable  // Blackwell Cluster BVH

// ─── Specialization Constants ─────────────────────────────────────────────
layout(constant_id = 0) const int GI_RAYS_PER_PIXEL  = 4;
layout(constant_id = 1) const int ENABLE_GI_TEMPORAL = 1;
layout(constant_id = 2) const int ENABLE_GI_SPATIAL  = 1;
layout(constant_id = 3) const int GI_MAX_M           = 10;
layout(constant_id = 4) const int GI_SPATIAL_SAMPLES = 1;

// 全局常數
const float GI_SPATIAL_RADIUS = 24.0;      // pixels
const float RAY_MAX_T         = 512.0;     // blocks（Minecraft 視距上限）
const float RAY_MIN_T         = 0.01;      // self-intersection 偏移
const float P_HAT_EPSILON     = 1e-6;
const float MAX_EMISSIVE_POWER = 1000.0;   // 與 BRSparseVoxelDAG 一致
const float TWO_PI            = 6.283185307;
const float PI                = 3.141592654;
const uint  BIT31             = 0x80000000u;

// SVDAG 節點 stride（uint32 為單位）：flags(1)+children(8)+albedo(1)+emissive(1) = 11
const uint DAG_NODE_STRIDE = 11u;
// Header size（uint32 為單位）= 10
const uint DAG_HEADER_SIZE = 10u;

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// ═══════════════════════════════════════════════════════════════════════════
//  SSBO 綁定
// ═══════════════════════════════════════════════════════════════════════════

// ─── Current GI Reservoir（write） ───────────────────────────────────────
// 格式：2 × uvec4 / pixel
//   uvec4[0]: floatBitsToUint(rayDir.xyz) + floatBitsToUint(hitDist)
//   uvec4[1]: floatBitsToUint(irrad.rgb) + M
layout(set = 0, binding = 0, scalar) buffer GIReservoirCurrent {
    uvec4 reservoirs[];   // 每像素 2 個 uvec4（索引 = pixelIdx * 2 + {0,1}）
} giCurrent;

// ─── Previous GI Reservoir（read-only） ──────────────────────────────────
layout(set = 0, binding = 1, scalar) readonly buffer GIReservoirPrevious {
    uvec4 prevReservoirs[];
} giPrevious;

// ─── DI Reservoir（read-only；提供直接光照已採樣的光源方向，供 GI 路徑評估）──
layout(set = 0, binding = 2, scalar) readonly buffer DIReservoir {
    uvec4 diReservoirs[];
} diBuffer;

// ─── SVDAG SSBO（BRSparseVoxelDAG.serializeForReSTIR() 輸出） ────────────
layout(set = 0, binding = 3, scalar) readonly buffer SVDAGBuffer {
    uint dagData[];
} svdag;

// ─── TLAS（ray query 可見性 + 次射線） ──────────────────────────────────
layout(set = 0, binding = 4) uniform accelerationStructureEXT u_TLAS;

// ─── GBuffer ─────────────────────────────────────────────────────────────
layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;
layout(set = 1, binding = 2) uniform sampler2D g_Albedo;   // primary hit albedo

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

layout(push_constant) uniform PushConstants {
    uint dagNodeCount;    // SVDAG 節點數量（0 = SVDAG 不可用）
    int  dagOriginX;      // SVDAG 世界座標原點 X
    int  dagOriginY;
    int  dagOriginZ;
    int  dagSize;         // SVDAG 覆蓋大小（= 1 << maxDepth）
    int  dagRootIdx;      // SVDAG 根節點索引
} pc;

// ═══════════════════════════════════════════════════════════════════════════
//  PCG 隨機數
// ═══════════════════════════════════════════════════════════════════════════

uint pcgHash(uint v) {
    uint s = v * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

uint initRng(ivec2 coord, ivec2 sz, uint frame) {
    uint pid = uint(coord.y) * uint(sz.x) + uint(coord.x);
    return pcgHash(pid ^ pcgHash(frame * 2654435761u));
}

float nextFloat(inout uint rng) {
    rng = pcgHash(rng);
    return float(rng) / 4294967295.0;
}

// ═══════════════════════════════════════════════════════════════════════════
//  GI Reservoir 工具
// ═══════════════════════════════════════════════════════════════════════════

struct GIReservoir {
    vec3  rayDir;   // 次射線方向（world space）
    float hitDist;  // 擊中距離
    vec3  irrad;    // 擊中點間接輻射量
    uint  M;        // 樣本計數
    float wSum;     // 累積權重（不存 GPU，只在 merge 時用）
    float W;        // 最終 RIS 估計權重
};

GIReservoir emptyGIReservoir() {
    GIReservoir r;
    r.rayDir  = vec3(0, 1, 0);
    r.hitDist = 0.0;
    r.irrad   = vec3(0.0);
    r.M       = 0u;
    r.wSum    = 0.0;
    r.W       = 0.0;
    return r;
}

// 讀取 GI Reservoir from SSBO（2 uvec4 per pixel）
GIReservoir loadGIReservoir(uint pixelIdx, bool fromPrev) {
    uint base = pixelIdx * 2u;
    uvec4 d0 = fromPrev ? giPrevious.prevReservoirs[base]   : giCurrent.reservoirs[base];
    uvec4 d1 = fromPrev ? giPrevious.prevReservoirs[base+1u]: giCurrent.reservoirs[base+1u];

    GIReservoir r;
    r.rayDir  = vec3(uintBitsToFloat(d0.x), uintBitsToFloat(d0.y), uintBitsToFloat(d0.z));
    r.hitDist = uintBitsToFloat(d0.w);
    r.irrad   = vec3(uintBitsToFloat(d1.x), uintBitsToFloat(d1.y), uintBitsToFloat(d1.z));
    r.M       = d1.w;
    r.wSum    = 0.0;  // wSum 不儲存於 GPU
    r.W       = 0.0;  // W 重新計算
    return r;
}

// 寫入 GI Reservoir to current SSBO
void storeGIReservoir(uint pixelIdx, GIReservoir r) {
    uint base = pixelIdx * 2u;
    giCurrent.reservoirs[base]   = uvec4(
        floatBitsToUint(r.rayDir.x),
        floatBitsToUint(r.rayDir.y),
        floatBitsToUint(r.rayDir.z),
        floatBitsToUint(r.hitDist)
    );
    giCurrent.reservoirs[base+1u] = uvec4(
        floatBitsToUint(r.irrad.r),
        floatBitsToUint(r.irrad.g),
        floatBitsToUint(r.irrad.b),
        r.M
    );
}

// ITU-R BT.709 luminance
float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// GI RIS weight
float computeGIW(GIReservoir r, float pHatAtY) {
    if (pHatAtY <= P_HAT_EPSILON || r.M == 0u) return 0.0;
    return (1.0 / pHatAtY) * (r.wSum / float(r.M));
}

// 合併 GI reservoir（回傳是否接受 src）
bool mergeGI(inout GIReservoir dst, GIReservoir src,
             float pHatSrc, float rand, int mCap) {
    uint  srcMCapped = min(src.M, uint(mCap));
    float srcContrib = pHatSrc * src.W * float(srcMCapped);
    dst.wSum        += srcContrib;
    dst.M           += srcMCapped;
    return rand < (srcContrib / dst.wSum);
}

// ═══════════════════════════════════════════════════════════════════════════
//  GBuffer 工具
// ═══════════════════════════════════════════════════════════════════════════

vec3 worldPosFromDepth(ivec2 coord, ivec2 imgSize) {
    vec2  uv    = (vec2(coord) + 0.5) / vec2(imgSize);
    float depth = texelFetch(g_Depth, coord, 0).r;
    vec4  ndc   = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4  world = cam.invViewProj * ndc;
    return world.xyz / world.w;
}

vec3 decodeNormal(ivec2 coord) {
    vec2 e = texelFetch(g_Normal, coord, 0).xy * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
    return normalize(n);
}

bool isValidPixel(ivec2 coord) {
    return texelFetch(g_Depth, coord, 0).r < 0.9999;
}

// ═══════════════════════════════════════════════════════════════════════════
//  餘弦加權半球採樣（Malley 方法）
// ═══════════════════════════════════════════════════════════════════════════

// 在法線 N 的半球上採樣方向，PDF = cosTheta / PI
vec3 cosineHemisphereSample(vec3 N, float r1, float r2) {
    // 同心圓盤採樣 → 半球
    float phi  = TWO_PI * r1;
    float sinT = sqrt(r2);
    float cosT = sqrt(1.0 - r2);

    vec3 localDir = vec3(cos(phi) * sinT, sin(phi) * sinT, cosT);

    // 建立切線基底（TBN）
    vec3 up = abs(N.y) < 0.999 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 T  = normalize(cross(up, N));
    vec3 B  = cross(N, T);

    return normalize(T * localDir.x + B * localDir.y + N * localDir.z);
}

// PDF of cosine hemisphere sample
float cosineHemispherePDF(float cosTheta) {
    return max(cosTheta, 0.0) / PI;
}

// ═══════════════════════════════════════════════════════════════════════════
//  SVDAG 光線遍歷（DDA + 子八叉樹下降）
//
//  參考：Kampe et al. 2013（SVDAG），Laine & Karras 2010（SVO ray casting）
//
//  演算法：
//    1. 從根節點開始，確定光線進入的子八叉樹
//    2. 若子節點存在（childMask），下降一層
//    3. 若為葉節點，計算擊中點並讀取材料屬性
//    4. 若子節點不存在，步進到下一個相鄰子八叉樹（DDA）
//    5. 重複至離開 DAG 或達到最大迭代次數
// ═══════════════════════════════════════════════════════════════════════════

// 讀取 DAG 節點欄位
uint dagFlags(uint nodeIdx)              { return svdag.dagData[DAG_HEADER_SIZE + nodeIdx * DAG_NODE_STRIDE]; }
uint dagChild(uint nodeIdx, int octant) { return svdag.dagData[DAG_HEADER_SIZE + nodeIdx * DAG_NODE_STRIDE + 1u + uint(octant)]; }
uint dagAlbedo(uint nodeIdx)             { return svdag.dagData[DAG_HEADER_SIZE + nodeIdx * DAG_NODE_STRIDE + 9u]; }
uint dagEmissive(uint nodeIdx)           { return svdag.dagData[DAG_HEADER_SIZE + nodeIdx * DAG_NODE_STRIDE + 10u]; }

bool dagIsLeaf(uint nodeIdx) {
    return (dagFlags(nodeIdx) & BIT31) == 0u && (dagFlags(nodeIdx) & 0xFF) == 0u;
}

vec4 unpackAlbedo(uint nodeIdx) {
    uvec4 c = (uvec4(dagAlbedo(nodeIdx)) >> uvec4(0, 8, 16, 24)) & 0xFFu;
    return vec4(c) / 255.0;
}

vec4 unpackEmissive(uint nodeIdx) {
    uvec4 c = (uvec4(dagEmissive(nodeIdx)) >> uvec4(0, 8, 16, 24)) & 0xFFu;
    return vec4(c) / 255.0;
}

// 結果結構
struct DAGHit {
    bool  hit;
    float t;         // 擊中距離（block 單位）
    vec3  hitPos;    // 世界座標擊中點
    vec3  normal;    // 面法線（軸對齊）
    vec3  albedo;    // 線性 sRGB
    vec3  emission;  // HDR emissive color
    float emissivePow;
};

DAGHit dagTraceRay(vec3 rayOrigin, vec3 rayDir, float tMax) {
    DAGHit result;
    result.hit = false;
    result.t   = tMax;

    if (pc.dagNodeCount == 0u) return result;

    // DAG 覆蓋的 AABB（world space，block 為單位）
    vec3 dagMin = vec3(pc.dagOriginX, pc.dagOriginY, pc.dagOriginZ);
    vec3 dagMax = dagMin + float(pc.dagSize);

    // 計算光線進入/離開 DAG AABB
    vec3 invDir = 1.0 / (abs(rayDir) + 1e-8) * sign(rayDir + 1e-9);
    vec3 t0     = (dagMin - rayOrigin) * invDir;
    vec3 t1     = (dagMax - rayOrigin) * invDir;
    vec3 tNear  = min(t0, t1);
    vec3 tFar   = max(t0, t1);
    float tEnter = max(max(tNear.x, tNear.y), max(tNear.z, 0.0));
    float tExit  = min(min(tFar.x,  tFar.y),  tFar.z);

    if (tEnter >= tExit || tEnter >= tMax) return result;

    // 迭代下降堆疊（最大深度 = maxDepth，此處用固定深度 14 支援最大 16384³）
    // 使用簡化的 ray-AABB + 逐層下降（適合 GPU 無遞迴）
    // 詳細實作：以 DDA 在當前層級步進，遇到有效子節點時下降
    uint  nodeStack[15];
    vec3  aabbMinStack[15];
    float sizeStack[15];
    int   stackDepth = 0;

    nodeStack[0]    = uint(pc.dagRootIdx);
    aabbMinStack[0] = dagMin;
    sizeStack[0]    = float(pc.dagSize);

    // 起始進入位置（world space）
    vec3 pos = rayOrigin + rayDir * (tEnter + 1e-4);

    // 最多 256 次迭代防無窮
    for (int iter = 0; iter < 256 && stackDepth >= 0; iter++) {
        if (stackDepth < 0) break;

        uint  nodeIdx  = nodeStack[stackDepth];
        vec3  aabbMin  = aabbMinStack[stackDepth];
        float halfSize = sizeStack[stackDepth] * 0.5;
        vec3  center   = aabbMin + halfSize;

        // 判斷 pos 落在哪個子八叉樹
        ivec3 oct   = ivec3(greaterThanEqual(pos, center));
        int   octIdx = oct.x | (oct.y << 1) | (oct.z << 2);

        uint childMask = dagFlags(nodeIdx) & 0xFFu;
        bool hasChild  = (childMask & (1u << octIdx)) != 0u;

        if (!hasChild || halfSize < 0.5) {
            // 葉層或空子節點：若有子節點則為擊中
            if (hasChild && halfSize < 0.5) {
                // 找到葉節點
                vec3 childMin = aabbMin + vec3(oct) * halfSize;
                vec3 t0c = (childMin - rayOrigin) * invDir;
                vec3 t1c = (childMin + halfSize - rayOrigin) * invDir;
                float tHit = max(max(min(t0c.x,t1c.x), min(t0c.y,t1c.y)), min(t0c.z,t1c.z));

                if (tHit >= 0.0 && tHit < result.t) {
                    // 計算面法線（離開方向的反向）
                    vec3 tMin3 = min(t0c, t1c);
                    vec3 n = vec3(0.0);
                    if (tMin3.x > tMin3.y && tMin3.x > tMin3.z) n.x = -sign(rayDir.x);
                    else if (tMin3.y > tMin3.z)                  n.y = -sign(rayDir.y);
                    else                                          n.z = -sign(rayDir.z);

                    vec4 alb   = unpackAlbedo(nodeIdx);
                    vec4 emiss = unpackEmissive(nodeIdx);

                    result.hit        = true;
                    result.t          = tHit;
                    result.hitPos     = rayOrigin + rayDir * tHit;
                    result.normal     = n;
                    result.albedo     = alb.rgb;
                    result.emission   = emiss.rgb;
                    result.emissivePow = emiss.a * MAX_EMISSIVE_POWER;
                }
            }
            // 步進到下一個子八叉樹（DDA）
            vec3 childMin = aabbMin + vec3(oct) * halfSize;
            vec3 t0c = (childMin - rayOrigin) * invDir;
            vec3 t1c = (childMin + halfSize - rayOrigin) * invDir;
            vec3 tFar3 = max(t0c, t1c);
            float tNext = min(min(tFar3.x, tFar3.y), tFar3.z) + 1e-4;

            pos = rayOrigin + rayDir * tNext;

            // 若離開當前節點的 AABB，彈出堆疊
            vec3 nodeMax = aabbMin + sizeStack[stackDepth];
            if (any(lessThan(pos, aabbMin)) || any(greaterThanEqual(pos, nodeMax))) {
                stackDepth--;
            }
        } else {
            // 下降子節點
            // 計算 compact child index（popcount 之前的子節點數）
            int compactIdx = 0;
            for (int o = 0; o < octIdx; o++) {
                if ((childMask & (1u << o)) != 0u) compactIdx++;
            }
            uint childNodeIdx = dagChild(nodeIdx, compactIdx);

            stackDepth++;
            if (stackDepth >= 15) { stackDepth--; break; }

            nodeStack[stackDepth]    = childNodeIdx;
            aabbMinStack[stackDepth] = aabbMin + vec3(oct) * halfSize;
            sizeStack[stackDepth]    = halfSize;
        }
    }

    return result;
}

// ═══════════════════════════════════════════════════════════════════════════
//  TLAS 可見性（Ray Query）
// ═══════════════════════════════════════════════════════════════════════════

float testVisibilityRay(vec3 origin, vec3 dir, float maxT) {
    rayQueryEXT q;
    rayQueryInitializeEXT(q, u_TLAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
        0xFF, origin + dir * RAY_MIN_T, 0.0, dir, maxT - RAY_MIN_T);
    while (rayQueryProceedEXT(q)) rayQueryTerminateEXT(q);
    return (rayQueryGetIntersectionTypeEXT(q, true) ==
            gl_RayQueryCommittedIntersectionNoneEXT) ? 1.0 : 0.0;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 0：次射線採樣
// ═══════════════════════════════════════════════════════════════════════════

GIReservoir candidateGISampling(vec3 worldPos, vec3 worldNormal,
                                 vec3 primaryAlbedo, inout uint rng) {
    GIReservoir best = emptyGIReservoir();

    for (int i = 0; i < GI_RAYS_PER_PIXEL; i++) {
        float r1 = nextFloat(rng);
        float r2 = nextFloat(rng);

        // 餘弦加權次射線方向
        vec3  rayDir = cosineHemisphereSample(worldNormal, r1, r2);
        float cosTheta = max(dot(worldNormal, rayDir), 0.0);
        float pdf      = cosineHemispherePDF(cosTheta);
        if (pdf <= P_HAT_EPSILON) continue;

        // SVDAG 遍歷找擊中點
        DAGHit hit = dagTraceRay(worldPos + worldNormal * RAY_MIN_T, rayDir, RAY_MAX_T);

        vec3  sampleIrrad;
        float hitDist;

        if (hit.hit) {
            // 擊中幾何：計算擊中點的間接輻射
            hitDist = hit.t;

            // 擊中點的直接光照（陽光 Lambertian 近似）
            float sunCos = max(dot(hit.normal, cam.sunDir), 0.0);
            float vis    = testVisibilityRay(hit.hitPos + hit.normal * RAY_MIN_T,
                                             cam.sunDir, RAY_MAX_T);
            vec3  direct = hit.albedo * cam.sunColor * sunCos * vis;

            // 自發光貢獻
            vec3  emitted = hit.emission * hit.emissivePow;

            // 間接輻射 = 自發光 + 直接光照（一跳近似）
            sampleIrrad = emitted + direct;
        } else {
            // 未擊中：天空輻射（Lambertian 天空模型）
            hitDist     = RAY_MAX_T;
            float skyDot = max(rayDir.y * 0.5 + 0.5, 0.0);
            sampleIrrad  = cam.skyColor * skyDot;
        }

        // p_hat = luminance × cosTheta（cosTheta 已含在 PDF 中，故 p_hat = lum）
        float candLum  = lum(sampleIrrad);
        float weight   = candLum / pdf;

        // RIS 更新（best 即為 reservoir）
        best.wSum += weight;
        best.M    += 1u;
        float acceptRand = nextFloat(rng);
        if (acceptRand < (weight / best.wSum)) {
            best.rayDir  = rayDir;
            best.hitDist = hitDist;
            best.irrad   = sampleIrrad;
        }
    }

    // 計算初始 W
    float pHatY = lum(best.irrad);
    best.W = computeGIW(best, pHatY);

    return best;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 1：時域重用
// ═══════════════════════════════════════════════════════════════════════════

GIReservoir temporalGIReuse(GIReservoir current,
                             vec3 worldPos, vec3 worldNormal,
                             ivec2 coord, ivec2 imgSize,
                             inout uint rng) {
    // 重投影
    vec4  prevClip  = cam.prevInvViewProj * vec4(worldPos, 1.0);
    prevClip.xyz   /= prevClip.w;
    ivec2 prevCoord = ivec2(prevClip.xy * 0.5 + 0.5) * imgSize - 1;

    if (any(lessThan(prevCoord, ivec2(0))) ||
        any(greaterThanEqual(prevCoord, imgSize))) return current;

    // 深度/法線相似性
    float dCurr = texelFetch(g_Depth, coord,     0).r;
    float dPrev = texelFetch(g_Depth, prevCoord, 0).r;
    if (abs(dCurr - dPrev) > 0.01) return current;

    vec3 nPrev = decodeNormal(prevCoord);
    if (dot(worldNormal, nPrev) < 0.906) return current;

    uint prevIdx = uint(prevCoord.y) * uint(imgSize.x) + uint(prevCoord.x);
    GIReservoir prev = loadGIReservoir(prevIdx, true);
    if (prev.M == 0u) return current;

    // p_hat：從 dst shading point 評估 prev 選中方向的間接輻射量
    // 簡化：使用 prev 儲存的 irradiance luminance 作為 p_hat（近似，不重新 trace）
    float pHatPrev = lum(prev.irrad);

    GIReservoir combined = current;
    float rand = nextFloat(rng);
    if (mergeGI(combined, prev, pHatPrev, rand, GI_MAX_M)) {
        combined.rayDir  = prev.rayDir;
        combined.hitDist = prev.hitDist;
        combined.irrad   = prev.irrad;
    }

    float pHatCombined = lum(combined.irrad);
    combined.W = computeGIW(combined, pHatCombined);
    return combined;
}

// ═══════════════════════════════════════════════════════════════════════════
//  Pass 2：空間重用
// ═══════════════════════════════════════════════════════════════════════════

GIReservoir spatialGIReuse(GIReservoir current,
                            vec3 worldPos, vec3 worldNormal,
                            ivec2 coord, ivec2 imgSize,
                            inout uint rng) {
    GIReservoir combined = current;

    for (int s = 0; s < GI_SPATIAL_SAMPLES; s++) {
        float theta  = nextFloat(rng) * TWO_PI;
        float radius = sqrt(nextFloat(rng)) * GI_SPATIAL_RADIUS;
        ivec2 nCoord = coord + ivec2(cos(theta) * radius, sin(theta) * radius);

        if (any(lessThan(nCoord, ivec2(0))) ||
            any(greaterThanEqual(nCoord, imgSize))) continue;
        if (!isValidPixel(nCoord)) continue;

        vec3 nNormal = decodeNormal(nCoord);
        if (dot(worldNormal, nNormal) < 0.906) continue;

        float dCurr = texelFetch(g_Depth, coord,  0).r;
        float dN    = texelFetch(g_Depth, nCoord, 0).r;
        if (abs(dCurr - dN) > 0.02) continue;

        uint nIdx = uint(nCoord.y) * uint(imgSize.x) + uint(nCoord.x);
        GIReservoir neighbor = loadGIReservoir(nIdx, false);
        if (neighbor.M == 0u) continue;

        float pHatN = lum(neighbor.irrad);
        float rand  = nextFloat(rng);
        if (mergeGI(combined, neighbor, pHatN, rand, 65536)) {
            combined.rayDir  = neighbor.rayDir;
            combined.hitDist = neighbor.hitDist;
            combined.irrad   = neighbor.irrad;
        }
    }

    float pHatFinal = lum(combined.irrad);
    combined.W = computeGIW(combined, pHatFinal);
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

    // 天空 / 背景：輸出空 GI reservoir
    if (!isValidPixel(coord)) {
        storeGIReservoir(pixelIdx, emptyGIReservoir());
        return;
    }

    // ─── GBuffer 解碼 ──────────────────────────────────────────────────────
    vec3 worldPos    = worldPosFromDepth(coord, imgSize);
    vec3 worldNormal = decodeNormal(coord);
    vec3 primaryAlb  = texelFetch(g_Albedo, coord, 0).rgb;

    // ─── RNG ──────────────────────────────────────────────────────────────
    uint rng = initRng(coord, imgSize, cam.frameIndex + 1000u);  // +offset 與 DI 區分

    // ─── Pass 0：次射線採樣 ────────────────────────────────────────────────
    GIReservoir result = candidateGISampling(worldPos, worldNormal, primaryAlb, rng);

    // ─── Pass 1：時域重用 ──────────────────────────────────────────────────
    if (ENABLE_GI_TEMPORAL == 1) {
        result = temporalGIReuse(result, worldPos, worldNormal, coord, imgSize, rng);
    }

    // ─── Pass 2：空間重用 ──────────────────────────────────────────────────
    if (ENABLE_GI_SPATIAL == 1) {
        result = spatialGIReuse(result, worldPos, worldNormal, coord, imgSize, rng);
    }

    // ─── 寫入 ─────────────────────────────────────────────────────────────
    storeGIReservoir(pixelIdx, result);
}
