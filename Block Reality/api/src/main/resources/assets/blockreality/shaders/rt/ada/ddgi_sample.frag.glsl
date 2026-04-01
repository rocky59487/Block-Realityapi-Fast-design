#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — DDGI Probe Sample Fragment Shader  (Phase 4, RT-4-2)
//  Dynamic Diffuse Global Illumination — Per-Pixel Probe Interpolation
//  Target: Ada SM8.9（RTX 40xx）
//
//  此 shader 在 full-screen quad pass 中執行，為每個 GBuffer 像素從周圍
//  8 個 probe 插值取得漫反射 GI 貢獻：
//    1. 從 g_Depth + invViewProj 重建世界座標
//    2. 計算包圍此像素的 8 個 probe（三線性網格）
//    3. 以 Chebyshev visibility test 計算每個 probe 的加權係數
//    4. 從 Irradiance Atlas 的 Octahedral 貼圖讀取各 probe 的輻射量
//    5. 三線性插值 → 乘以像素 albedo → 漫反射 GI 貢獻
//
//  輸出格式：RGBA16F（RGB = GI 漫反射貢獻，A = 信心係數）
//  使用於 Ada 管線路徑：
//    BLAS_TLAS_UPDATE → RT_SHADOW_AO → DDGI_UPDATE → DDGI_SAMPLE → NRD → ...
//
//  參考文獻：
//    Majercik et al. 2019, §4 "Shading with Irradiance Fields"
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_scalar_block_layout : require

// ─── 輸出 ─────────────────────────────────────────────────────────────────
layout(location = 0) out vec4 o_GIDiffuse;   // RGB = GI 漫反射，A = 信心

// ─── GBuffer ─────────────────────────────────────────────────────────────
layout(set = 0, binding = 0) uniform sampler2D g_Depth;
layout(set = 0, binding = 1) uniform sampler2D g_Normal;
layout(set = 0, binding = 2) uniform sampler2D g_Albedo;

// ─── DDGI Atlas ──────────────────────────────────────────────────────────
layout(set = 0, binding = 3) uniform sampler2D irradianceAtlas;
layout(set = 0, binding = 4) uniform sampler2D visibilityAtlas;

// ─── Probe UBO ────────────────────────────────────────────────────────────
layout(set = 0, binding = 5, scalar) readonly buffer ProbeUBO {
    vec4 probes[];   // xyz = world_pos, w = flags
} probeUbo;

// ─── Camera UBO ──────────────────────────────────────────────────────────
layout(set = 1, binding = 0, scalar) uniform CameraUBO {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec3  camPos;     float _p0;
    vec3  sunDir;     float _p1;
    vec3  sunColor;   float _p2;
    vec3  skyColor;   float _p3;
    uint  frameIndex;
    float _pad[7];
} cam;

// ─── Push Constants ───────────────────────────────────────────────────────
layout(push_constant) uniform PushConstants {
    int   gridX;
    int   gridY;
    int   gridZ;
    int   spacingBlocks;
    int   gridOriginX;
    int   gridOriginY;
    int   gridOriginZ;
    int   atlasProbesPerRow;  // gridX × gridZ
    int   irradTexels;        // = 8
    int   visTexels;          // = 8
    float irradBias;          // 法線偏移（防自遮擋，建議 0.3）
    float visibilityBias;     // Chebyshev 偏移（建議 0.1）
} pc;

const int   PROBE_IRRAD_FULL = 10; // IRRAD_TEXELS + 2 * BORDER = 8 + 2
const int   PROBE_VIS_FULL   = 10;
const float CHEBYSHEV_MIN_VAR = 1e-4;

// ═══════════════════════════════════════════════════════════════════════════
//  工具函式
// ═══════════════════════════════════════════════════════════════════════════

vec3 worldPosFromDepth(vec2 uv, float depth) {
    vec4 ndc   = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = cam.invViewProj * ndc;
    return world.xyz / world.w;
}

vec3 decodeNormal(vec2 uv) {
    vec2 e = texture(g_Normal, uv).xy * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
    return normalize(n);
}

// 線性 probe 索引 → Atlas UV（取 Irradiance texel 的中心 UV）
vec2 probeIrradianceUV(int probeIdx, vec3 dir) {
    // Octahedral 投影
    float absSum = abs(dir.x) + abs(dir.y) + abs(dir.z);
    vec2  oct    = dir.xz / absSum;
    if (dir.y < 0.0) oct = (1.0 - abs(oct.yx)) * sign(oct);

    // probe-local texel 座標（含 border）
    vec2 localUV = (oct * 0.5 + 0.5) * float(pc.irradTexels);
    localUV      = clamp(localUV, 0.5, float(pc.irradTexels) - 0.5);
    // border offset
    localUV     += 1.0;

    // atlas pixel origin
    int   col      = probeIdx % pc.atlasProbesPerRow;
    int   row      = probeIdx / pc.atlasProbesPerRow;
    vec2  atlasOrig = vec2(col * PROBE_IRRAD_FULL, row * PROBE_IRRAD_FULL);

    // 轉為 atlas UV（歸一化）
    ivec2 atlasSize = textureSize(irradianceAtlas, 0);
    return (atlasOrig + localUV) / vec2(atlasSize);
}

// Visibility atlas UV（相同邏輯）
vec2 probeVisibilityUV(int probeIdx, vec3 dir) {
    float absSum = abs(dir.x) + abs(dir.y) + abs(dir.z);
    vec2  oct    = dir.xz / absSum;
    if (dir.y < 0.0) oct = (1.0 - abs(oct.yx)) * sign(oct);

    vec2 localUV = (oct * 0.5 + 0.5) * float(pc.visTexels) + 1.0;
    localUV = clamp(localUV, 0.5, float(pc.visTexels) + 0.5);

    int col = probeIdx % pc.atlasProbesPerRow;
    int row = probeIdx / pc.atlasProbesPerRow;
    vec2 atlasOrig = vec2(col * PROBE_VIS_FULL, row * PROBE_VIS_FULL);

    ivec2 atlasSize = textureSize(visibilityAtlas, 0);
    return (atlasOrig + localUV) / vec2(atlasSize);
}

// Chebyshev Visibility Weight（Variance Shadow Map 概念）
// 估計 probe 到遮擋點之間的可見性
float chebyshevWeight(int probeIdx, vec3 probeDir, float trueDist) {
    vec2  visUV  = probeVisibilityUV(probeIdx, probeDir);
    vec2  vis    = texture(visibilityAtlas, visUV).rg;
    float mean   = vis.r;
    float var    = max(vis.g * vis.g, CHEBYSHEV_MIN_VAR);

    if (trueDist <= mean + pc.visibilityBias) return 1.0;

    // Chebyshev 不等式上界：P(d > trueDist) ≤ var / (var + (d - mean)²)
    float diff = trueDist - mean;
    return var / (var + diff * diff);
}

// ═══════════════════════════════════════════════════════════════════════════
//  Main
// ═══════════════════════════════════════════════════════════════════════════

void main() {
    vec2 uv    = gl_FragCoord.xy / vec2(textureSize(g_Depth, 0));
    float depth = texture(g_Depth, uv).r;

    // 天空 / 背景
    if (depth >= 0.9999) {
        o_GIDiffuse = vec4(0.0);
        return;
    }

    vec3 worldPos    = worldPosFromDepth(uv, depth);
    vec3 worldNormal = decodeNormal(uv);
    vec3 albedo      = texture(g_Albedo, uv).rgb;

    // ─── 找到包圍點的 8 個 probe ────────────────────────────────────────
    vec3  relPos = (worldPos - vec3(pc.gridOriginX, pc.gridOriginY, pc.gridOriginZ))
                   / float(pc.spacingBlocks);
    ivec3 base   = ivec3(floor(relPos));
    vec3  frac   = fract(relPos);

    vec3  irradSum  = vec3(0.0);
    float weightSum = 0.0;
    float confSum   = 0.0;

    for (int i = 0; i < 8; i++) {
        int dx = (i & 1);
        int dy = (i >> 1 & 1);
        int dz = (i >> 2 & 1);
        ivec3 idx3 = base + ivec3(dx, dy, dz);

        // 邊界裁剪
        if (any(lessThan(idx3, ivec3(0))) ||
            idx3.x >= pc.gridX || idx3.y >= pc.gridY || idx3.z >= pc.gridZ) continue;

        int probeIdx = idx3.y * pc.gridX * pc.gridZ + idx3.z * pc.gridX + idx3.x;
        vec3 probePos = probeUbo.probes[probeIdx].xyz;

        // 三線性插值係數
        vec3  t3      = mix(1.0 - frac, frac, vec3(dx, dy, dz));
        float triW    = t3.x * t3.y * t3.z;
        if (triW <= 1e-6) continue;

        // 從世界座標到 probe 的方向（法線偏移）
        vec3  toProbe = probePos - (worldPos + worldNormal * pc.irradBias);
        float distToProbe = length(toProbe);
        if (distToProbe < 1e-4) continue;
        vec3  probeDir = toProbe / distToProbe;

        // Chebyshev 可見性（從 probe 角度看 worldPos）
        // probe 方向（從 probe 看向 worldPos）= -probeDir
        float visW = chebyshevWeight(probeIdx, -probeDir, distToProbe);
        // 平方以增強對比（Majercik 建議）
        visW = visW * visW;
        // 法線角度過濾（背面 probe 權重降低）
        float cosNL  = max(dot(worldNormal, normalize(-toProbe)), 0.0);
        float backW  = max(0.1, cosNL + 0.2); // 永遠保留 10% 防黑色瑕疵

        float finalW = triW * visW * backW;

        // 讀取 Irradiance Atlas
        vec3 irrad = texture(irradianceAtlas, probeIrradianceUV(probeIdx, worldNormal)).rgb;

        irradSum  += irrad * finalW;
        weightSum += finalW;
        confSum   += triW;
    }

    vec3  giIrrad = (weightSum > 1e-6) ? (irradSum / weightSum) : vec3(0.0);
    float conf    = clamp(confSum, 0.0, 1.0);

    // GI 漫反射貢獻 = irradiance × albedo / π（Lambertian BRDF 分母）
    vec3 giDiffuse = giIrrad * albedo / 3.141592654;

    o_GIDiffuse = vec4(max(giDiffuse, vec3(0.0)), conf);
}
