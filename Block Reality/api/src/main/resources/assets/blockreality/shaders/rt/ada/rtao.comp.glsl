#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — RTAO Compute Shader（Ada / Blackwell Ray Query）
//
//  優於 in-raygen RTAO：
//  - 計算著色器有更大的 shared memory（wave-level 統計）
//  - 可以 barrier + 時域累積在同一個 pass
//  - Blackwell: Thread Block Cluster 跨 tile 共用 AO 結果
//
//  SC 0: GPU_TIER    (0=Ada SM8.9, 1=Blackwell SM10)
//  SC 1: AO_SAMPLES  (8=Ada, 16=Blackwell)
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_query             : require
#extension GL_EXT_scalar_block_layout   : require
#extension GL_KHR_shader_subgroup_arithmetic : require
#extension GL_NV_shader_sm_builtins     : require  // gl_WarpIDNV, gl_SMIDNV

// Blackwell: Thread Block Clusters（Compute 9.0 以上）
#ifdef GL_NV_cluster_acceleration_structure
#extension GL_NV_cluster_acceleration_structure : require
#endif

layout(constant_id = 0) const int GPU_TIER   = 0;
layout(constant_id = 1) const int AO_SAMPLES = 8;  // Blackwell 時設為 16

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// ─── Bindings ────────────────────────────────────────────────────────────
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;
layout(set = 0, binding = 4, rg16f) uniform image2D u_AOOutput; // RG: [ao, indirect_lum]

layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;

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

// 時域 AO 歷史（讀取）
layout(set = 0, binding = 5, rg16f) uniform readonly image2D u_AOHistory;

// ─── Shared Memory（8×8 tile AO 值，用於 bilateral blur） ────────────────
shared float s_AO[8 + 2][8 + 2]; // +1 padding 每邊

// ─── 工具函式 ─────────────────────────────────────────────────────────────
uint pcgHash(uint seed) {
    uint s = seed * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

vec3 decodeNormal(vec2 enc) {
    vec2 f = enc * 2.0 - 1.0;
    vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
    float t = clamp(-n.z, 0.0, 1.0);
    n.xy += mix(vec2(-t), vec2(t), step(0.0, n.xy));
    return normalize(n);
}

vec3 reconstructWorld(vec2 uv, float depth) {
    vec4 clip  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = cam.invViewProj * clip;
    return world.xyz / world.w;
}

mat3 buildTBN(vec3 N) {
    vec3 up = abs(N.y) < 0.9 ? vec3(0,1,0) : vec3(1,0,0);
    vec3 T  = normalize(cross(up, N));
    return mat3(T, cross(N, T), N);
}

vec3 cosineSample(vec2 xi) {
    float phi = 6.28318530718 * xi.x;
    float c   = sqrt(1.0 - xi.y);
    return vec3(cos(phi) * sqrt(xi.y), sin(phi) * sqrt(xi.y), c);
}

// ─── 主要 RTAO 核心（Ray Query） ─────────────────────────────────────────
float computeAO(vec3 worldPos, vec3 N, uint baseFrame) {
    mat3  tbn   = buildTBN(N);
    float aoSum = 0.0;

    for (int i = 0; i < AO_SAMPLES; i++) {
        // Blue noise: 每樣本用不同 seed 組合
        uint sid = gl_GlobalInvocationID.x * 1234567u
                 ^ gl_GlobalInvocationID.y * 7654321u
                 ^ (baseFrame + uint(i)) * 987654u;
        sid = pcgHash(pcgHash(sid));

        vec2 xi    = vec2(float(sid & 0xFFFFu), float(sid >> 16u)) / 65535.0;
        xi         = fract(xi + vec2(0.754877669, 0.569840290) * float(i)); // golden ratio jitter
        vec3 aoDir = tbn * cosineSample(xi);

        rayQueryEXT rq;
        rayQueryInitializeEXT(rq, u_TLAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            0xFF,                    // cullMask
            worldPos + N * 0.015,   // origin + bias
            0.01,                    // tMin
            aoDir,
            cam.aoRadius             // tMax（可設 8~32 blocks）
        );

        while (rayQueryProceedEXT(rq)) {
            // any-hit 不需要處理（已設 OPAQUE flag）
        }

        bool hit = (rayQueryGetIntersectionTypeEXT(rq, true)
                    != gl_RayQueryCommittedIntersectionNoneEXT);
        aoSum += hit ? 0.0 : 1.0;
    }

    return pow(aoSum / float(AO_SAMPLES), cam.aoStrength);
}

// ─── 時域累積（指數移動平均） ──────────────────────────────────────────────
// 混合比：90% 歷史 + 10% 當前（與 BRSVGFDenoiser 相同策略）
// Blackwell 可以用更大的歷史權重（16 samples 更穩定）
float blendWithHistory(ivec2 coord, float newAO) {
    vec2 hist  = imageLoad(u_AOHistory, coord).rg;
    float histAO = hist.r;
    float histLen = hist.g;          // 累積長度（最大 32）

    float alpha = GPU_TIER >= 1 ? 0.04 : 0.10; // Blackwell: 更慢混合 = 更穩定
    float blendLen = min(histLen + 1.0, 32.0);
    float blendAlpha = max(alpha, 1.0 / blendLen);

    return mix(histAO, newAO, blendAlpha);
}

// ─── 主函式 ──────────────────────────────────────────────────────────────
void main() {
    ivec2 coord  = ivec2(gl_GlobalInvocationID.xy);
    ivec2 imgSize = imageSize(u_AOOutput);

    if (any(greaterThanEqual(coord, imgSize))) return;

    vec2  uv    = (vec2(coord) + 0.5) / vec2(imgSize);
    float depth = texture(g_Depth, uv).r;

    if (depth >= 1.0) {
        imageStore(u_AOOutput, coord, vec4(1.0, 0.0, 0.0, 0.0));
        return;
    }

    vec3  worldPos = reconstructWorld(uv, depth);
    vec3  N        = decodeNormal(texture(g_Normal, uv).rg);

    // ── RTAO 計算 ────────────────────────────────────────────────────────
    float ao = computeAO(worldPos, N, cam.frameIndex);

    // ── 時域混合 ─────────────────────────────────────────────────────────
    float blendedAO = blendWithHistory(coord, ao);

    // ── Shared Memory bilateral 預填（供周邊 tile 查詢） ──────────────────
    int lx = int(gl_LocalInvocationID.x) + 1;
    int ly = int(gl_LocalInvocationID.y) + 1;
    s_AO[ly][lx] = blendedAO;

    // 填充邊界（tile 邊緣複製）
    if (gl_LocalInvocationID.x == 0u) s_AO[ly][0]        = blendedAO;
    if (gl_LocalInvocationID.x == 7u) s_AO[ly][9]        = blendedAO;
    if (gl_LocalInvocationID.y == 0u) s_AO[0][lx]        = blendedAO;
    if (gl_LocalInvocationID.y == 7u) s_AO[9][lx]        = blendedAO;

    barrier();

    // ── 3×3 bilateral blur（同 depth 範圍內的鄰近 AO 平滑） ──────────────
    float filteredAO = 0.0, wSum = 0.0;
    float centerDepth = depth;

    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            float s = s_AO[ly + dy][lx + dx];
            // 深度權重：防止跨邊界 AO 洩漏
            vec2  nUV   = (vec2(coord + ivec2(dx, dy)) + 0.5) / vec2(imgSize);
            float nDepth = texture(g_Depth, nUV).r;
            float dw    = exp(-abs(nDepth - centerDepth) * 200.0);
            filteredAO += s * dw;
            wSum       += dw;
        }
    }
    filteredAO = (wSum > 0.0) ? filteredAO / wSum : blendedAO;

    // ── 歷史長度追蹤（儲存在 G 通道） ─────────────────────────────────────
    float histLen = min(imageLoad(u_AOHistory, coord).g + 1.0, 32.0);

    imageStore(u_AOOutput, coord, vec4(filteredAO, histLen, 0.0, 0.0));
}
