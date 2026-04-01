#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — DDGI Probe Update Compute Shader  (Phase 4, RT-4-2)
//  Dynamic Diffuse Global Illumination — Probe Irradiance & Visibility Update
//  Target: Ada SM8.9（RTX 40xx）+ Blackwell fallback
//
//  每次 dispatch 更新 probesThisFrame 個 probe：
//    1. 從 probe 中心向 NUM_RAYS 個方向發射 ray（Fibonacci spiral 均勻分佈）
//    2. 追蹤 TLAS 找到擊中點，讀取直接光照（DI Reservoir）
//    3. 更新 probe Irradiance atlas（Octahedral Projection，指數移動平均）
//    4. 更新 probe Visibility atlas（mean hit distance + variance）
//
//  SC 0: NUM_RAYS          (每 probe 的射線數；Ada=64，可降至 32)
//  SC 1: IRRAD_TEXELS      (Irradiance probe 邊長；= 8)
//  SC 2: VIS_TEXELS        (Visibility probe 邊長；= 8)
//  SC 3: HYSTERESIS_IRRAD  (EMA 係數，float bits；預設 0.97，即 3% 更新)
//  SC 4: HYSTERESIS_VIS    (Visibility EMA 係數；預設 0.97)
//
//  參考文獻：
//    Majercik et al. 2019, "Dynamic Diffuse Global Illumination with
//    Ray-Traced Irradiance Fields"
//    Majercik et al. 2021, "Scaling Probe-Based Real-Time Dynamic
//    Global Illumination for Production"
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_query          : require
#extension GL_EXT_scalar_block_layout: require

// ─── Specialization Constants ─────────────────────────────────────────────
layout(constant_id = 0) const int   NUM_RAYS         = 64;
layout(constant_id = 1) const int   IRRAD_TEXELS     = 8;
layout(constant_id = 2) const int   VIS_TEXELS       = 8;
layout(constant_id = 3) const int   HYSTERESIS_IRRAD_BITS = 0x3F7851EC; // 0.97f
layout(constant_id = 4) const int   HYSTERESIS_VIS_BITS   = 0x3F7851EC;

// 每 probe texels（含 border=1）
const int PROBE_IRRAD_FULL = IRRAD_TEXELS + 2;
const int PROBE_VIS_FULL   = VIS_TEXELS   + 2;

const float TWO_PI    = 6.283185307;
const float PI        = 3.141592654;
const float RAY_MAX_T = 512.0;
const float RAY_MIN_T = 0.02;

// 每 workgroup 處理一個 probe；執行緒數 = NUM_RAYS（最大 64）
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

// ─── Irradiance Atlas（read-write） ──────────────────────────────────────
layout(set = 0, binding = 0, rgba16f) uniform image2D irradianceAtlas;

// ─── Visibility Atlas（read-write） ──────────────────────────────────────
layout(set = 0, binding = 1, rg16f) uniform image2D visibilityAtlas;

// ─── Probe UBO（probe world positions） ──────────────────────────────────
layout(set = 0, binding = 2, scalar) readonly buffer ProbeUBO {
    vec4 probes[];   // xyz = world_pos, w = flags
} probeUbo;

// ─── DI Reservoir（從 restir_di 讀取本幀直接光照結果） ────────────────────
// 由於 probe 更新不依賴螢幕空間，此 binding 為可選（無資料時使用太陽近似）
layout(set = 0, binding = 3, scalar) readonly buffer DIReservoir {
    uvec4 diReservoirs[];
} diBuffer;

// ─── TLAS ────────────────────────────────────────────────────────────────
layout(set = 0, binding = 4) uniform accelerationStructureEXT u_TLAS;

// ─── Camera / Scene UBO ──────────────────────────────────────────────────
layout(set = 1, binding = 0, scalar) uniform SceneUBO {
    vec3  sunDir;    float _p0;
    vec3  sunColor;  float _p1;
    vec3  skyColor;  float _p2;
    uint  frameIndex;
    float _pad[3];
} scene;

// ─── Push Constants ───────────────────────────────────────────────────────
layout(push_constant) uniform PushConstants {
    uint  firstProbeIdx;   // 本 dispatch 的起始 probe 線性索引
    uint  probeCount;      // 本 dispatch 更新的 probe 數量
    int   gridX;
    int   gridY;
    int   gridZ;
    int   spacingBlocks;
    int   gridOriginX;
    int   gridOriginY;
    int   gridOriginZ;
    int   atlasProbesPerRow;  // gridX × gridZ
} pc;

// ═══════════════════════════════════════════════════════════════════════════
//  共享記憶體（每 probe 64 條射線的輻射 + 距離）
// ═══════════════════════════════════════════════════════════════════════════

shared vec3  s_rayRadiance[64];  // HDR radiance per ray
shared float s_rayHitDist[64];   // hit distance per ray（0 = miss/sky）

// ═══════════════════════════════════════════════════════════════════════════
//  工具函式
// ═══════════════════════════════════════════════════════════════════════════

// Fibonacci spiral 射線方向（Hammersley 序列的近似）
// 在球面上均勻分佈 N 個方向
vec3 fibonacciRayDir(uint i, uint n, float frameOffset) {
    float phi  = TWO_PI * fract(float(i) / 1.618033988749 + frameOffset);
    float cosT = 1.0 - (2.0 * float(i) + 1.0) / float(n);
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    return vec3(cos(phi) * sinT, cosT, sin(phi) * sinT);
}

// Octahedral 方向 → Atlas UV（probe-local [0,TEXELS]² 空間）
ivec2 dirToOctTexel(vec3 dir, int texels) {
    float absSum = abs(dir.x) + abs(dir.y) + abs(dir.z);
    vec2 oct = dir.xz / absSum;
    if (dir.y < 0.0) {
        oct = (1.0 - abs(oct.yx)) * sign(oct);
    }
    // 映射到 [0, texels]
    ivec2 texel = ivec2(clamp((oct * 0.5 + 0.5) * float(texels), 0.0, float(texels - 1)));
    return texel;
}

// 取得 probe 在 atlas 中的起始像素（含 border offset）
ivec2 probeAtlasOrigin(uint probeIdx, int probeFullSize) {
    int col = int(probeIdx) % pc.atlasProbesPerRow;
    int row = int(probeIdx) / pc.atlasProbesPerRow;
    return ivec2(col * probeFullSize, row * probeFullSize) + ivec2(1); // +1 border
}

// ITU-R BT.709 luminance
float lum709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ═══════════════════════════════════════════════════════════════════════════
//  Main
// ═══════════════════════════════════════════════════════════════════════════

void main() {
    uint localRayIdx  = gl_LocalInvocationID.x;   // 0..NUM_RAYS-1（此幀的射線）
    uint probeOffset  = gl_WorkGroupID.x;          // 此 workgroup 處理的 probe（相對 firstProbeIdx）
    uint probeIdx     = pc.firstProbeIdx + probeOffset;

    if (probeIdx >= uint(pc.gridX * pc.gridY * pc.gridZ)) return;
    if (localRayIdx >= uint(NUM_RAYS)) return;

    // ─── 取得 probe 世界座標 ──────────────────────────────────────────────
    vec3 probePos = probeUbo.probes[probeIdx].xyz;

    // ─── 計算射線方向（Fibonacci spiral + frame 旋轉） ────────────────────
    float frameRot = fract(float(scene.frameIndex) * 0.618033988749);
    vec3 rayDir = fibonacciRayDir(localRayIdx, uint(NUM_RAYS), frameRot);

    // ─── Ray Query（TLAS 追蹤） ──────────────────────────────────────────
    rayQueryEXT q;
    rayQueryInitializeEXT(q, u_TLAS,
        gl_RayFlagsOpaqueEXT,
        0xFF,
        probePos + rayDir * RAY_MIN_T,
        0.0, rayDir, RAY_MAX_T);

    float hitDist     = RAY_MAX_T;
    bool  didHit      = false;
    while (rayQueryProceedEXT(q)) {
        if (rayQueryGetIntersectionTypeEXT(q, false) == gl_RayQueryCandidateIntersectionTriangleEXT) {
            rayQueryConfirmIntersectionEXT(q);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(q, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
        hitDist = rayQueryGetIntersectionTEXT(q, true);
        didHit  = true;
    }

    // ─── 計算擊中點輻射（簡化 1-bounce） ─────────────────────────────────
    vec3 radiance;
    if (didHit) {
        vec3 hitPos    = probePos + rayDir * hitDist;
        // 取得擊中幾何法線（ray query committed intersection）
        // 簡化：使用幾何法線計算陽光貢獻
        // 完整實作需要 attribute fetch（見 Phase 8 整合計畫）
        float sunCos = max(dot(normalize(vec3(0.1, 1.0, 0.2)), scene.sunDir), 0.0);
        // 陽光遮擋測試
        rayQueryEXT sq;
        rayQueryInitializeEXT(sq, u_TLAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            0xFF,
            hitPos + scene.sunDir * RAY_MIN_T,
            0.0, scene.sunDir, RAY_MAX_T - hitDist);
        while (rayQueryProceedEXT(sq)) rayQueryTerminateEXT(sq);
        float sunVis = (rayQueryGetIntersectionTypeEXT(sq, true) ==
                        gl_RayQueryCommittedIntersectionNoneEXT) ? 1.0 : 0.0;

        // 漫反射近似：albedo = 0.5（中性灰）× 陽光貢獻
        // Phase 8 整合：從 GBuffer material SSBO 讀取真實 albedo
        vec3 albedo  = vec3(0.5);
        radiance     = albedo * scene.sunColor * sunCos * sunVis;
    } else {
        // 天空：Lambertian 天空模型
        float skyDot = max(rayDir.y * 0.5 + 0.5, 0.0);
        radiance     = scene.skyColor * skyDot;
        hitDist      = 0.0;  // 0 表示 miss（用於 visibility）
    }

    // ─── 寫入共享記憶體 ───────────────────────────────────────────────────
    s_rayRadiance[localRayIdx] = radiance;
    s_rayHitDist[localRayIdx]  = hitDist;
    barrier();
    memoryBarrierShared();

    // ─── 執行緒 0 負責將射線結果累積到 Atlas ─────────────────────────────
    // 每個 texel 對應一個方向：找到落在該 texel 的射線並平均
    // 實作：每個 texel 由一個執行緒處理（IRRAD_TEXELS² ≤ 64 = NUM_RAYS）
    int texelIdx = int(localRayIdx);
    int irradTexels2 = IRRAD_TEXELS * IRRAD_TEXELS;

    if (texelIdx < irradTexels2) {
        int tx = texelIdx % IRRAD_TEXELS;
        int ty = texelIdx / IRRAD_TEXELS;

        // texel 中心方向
        vec2 uv  = (vec2(tx, ty) + 0.5) / float(IRRAD_TEXELS);
        vec2 oct = uv * 2.0 - 1.0;
        vec3 texDir = normalize(vec3(oct.x, 1.0 - abs(oct.x) - abs(oct.y),
                                     oct.y));
        if (1.0 - abs(oct.x) - abs(oct.y) < 0.0) {
            texDir.xz = (1.0 - abs(texDir.zx)) * sign(texDir.xz);
            texDir    = normalize(texDir);
        }

        // 收集落在此 texel 的射線（Jacobian 加權）
        vec3  accumIrrad = vec3(0.0);
        float accumW     = 0.0;
        for (int r = 0; r < NUM_RAYS; r++) {
            vec3  rd     = fibonacciRayDir(uint(r), uint(NUM_RAYS), frameRot);
            float cosW   = max(dot(rd, texDir), 0.0);
            if (cosW > 0.0) {
                accumIrrad += s_rayRadiance[r] * cosW;
                accumW     += cosW;
            }
        }

        vec3 newIrrad = (accumW > 0.0) ? (accumIrrad / accumW) : vec3(0.0);

        // EMA 更新（指數移動平均，Hysteresis）
        ivec2 atlasOrig = probeAtlasOrigin(probeIdx, PROBE_IRRAD_FULL);
        ivec2 atlasCoord = atlasOrig + ivec2(tx, ty);
        vec4 prevIrrad = imageLoad(irradianceAtlas, atlasCoord);

        float hyst   = uintBitsToFloat(uint(HYSTERESIS_IRRAD_BITS));
        vec3  blended = mix(newIrrad, prevIrrad.rgb, hyst);
        imageStore(irradianceAtlas, atlasCoord, vec4(blended, 1.0));
    }

    // ─── Visibility Atlas 更新 ────────────────────────────────────────────
    int visTexels2 = VIS_TEXELS * VIS_TEXELS;
    if (texelIdx < visTexels2) {
        int tx = texelIdx % VIS_TEXELS;
        int ty = texelIdx / VIS_TEXELS;

        vec2 uv  = (vec2(tx, ty) + 0.5) / float(VIS_TEXELS);
        vec2 oct = uv * 2.0 - 1.0;
        vec3 texDir = normalize(vec3(oct.x, 1.0 - abs(oct.x) - abs(oct.y), oct.y));

        // 計算此方向的平均擊中距離與距離²（供 Chebyshev 測試）
        float sumD = 0.0, sumD2 = 0.0, sumW = 0.0;
        for (int r = 0; r < NUM_RAYS; r++) {
            vec3  rd   = fibonacciRayDir(uint(r), uint(NUM_RAYS), frameRot);
            float cosW = max(dot(rd, texDir), 0.0);
            float d    = s_rayHitDist[r];
            if (cosW > 0.0 && d > 0.0) {
                sumD  += d    * cosW;
                sumD2 += d * d * cosW;
                sumW  += cosW;
            }
        }

        vec2 newVis = (sumW > 0.0)
            ? vec2(sumD / sumW, sqrt(max(0.0, sumD2 / sumW - (sumD/sumW)*(sumD/sumW))))
            : vec2(RAY_MAX_T, 0.0);

        ivec2 atlasOrig  = probeAtlasOrigin(probeIdx, PROBE_VIS_FULL);
        ivec2 atlasCoord = atlasOrig + ivec2(tx, ty);
        vec2 prevVis = imageLoad(visibilityAtlas, atlasCoord).rg;

        float hyst    = uintBitsToFloat(uint(HYSTERESIS_VIS_BITS));
        vec2  blended = mix(newVis, prevVis, hyst);
        imageStore(visibilityAtlas, atlasCoord, vec4(blended, 0.0, 0.0));
    }
}
