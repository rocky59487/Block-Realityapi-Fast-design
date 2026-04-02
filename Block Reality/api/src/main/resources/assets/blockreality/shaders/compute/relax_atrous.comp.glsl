#version 460

// ══════════════════════════════════════════════════════════════════════════════
// relax_atrous.comp.glsl — ReLAX 空間 A-trous 小波濾波（P2-A）
//
// 替換 BRSVGFDenoiser 的 OpenGL a-trous 通道，改以純 Vulkan compute 實作。
//
// 改進項目（相較 SVGF GL 版本）：
//   ✓ 純 Vulkan compute
//   ✓ 方差引導 sigma（低方差區域使用更緊的核，保留細節）
//   ✓ 法線邊緣停止（world-space XYZ，更精確邊界保留）
//   ✓ 5×5 a-trous 核（step size 可配置：1, 2, 4, 8 四次迭代）
//
// Binding 佈局（set 0）：
//   0  rgba16f STORAGE_IMAGE  u_inputColor（本次迭代輸入）
//   1  rgba16f STORAGE_IMAGE  u_outputColor（本次迭代輸出）
//   2  sampler2D              u_depthTex
//   3  sampler2D              u_normalTex（RGB 儲存 world-space normal 或 view-space XYZ）
//   4  rg32f   STORAGE_IMAGE  u_moments（時間累積產出 [mean, variance]，只讀）
//
// Push Constants（32 bytes）：
//   width, height, stepSize, sigmaDepth, sigmaNormal, sigmaLum, _pad0, _pad1
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0, rgba16f) uniform readonly  image2D u_inputColor;
layout(set = 0, binding = 1, rgba16f) uniform writeonly image2D u_outputColor;
layout(set = 0, binding = 2) uniform sampler2D u_depthTex;
layout(set = 0, binding = 3) uniform sampler2D u_normalTex;
layout(set = 0, binding = 4, rg32f) uniform readonly image2D u_moments;

layout(push_constant) uniform PC {
    uint  width;
    uint  height;
    uint  stepSize;     // 本次迭代的核擴展步長（1 → 2 → 4 → 8）
    float sigmaDepth;   // 深度邊緣停止 sigma（預設 0.1）
    float sigmaNormal;  // 法線邊緣停止冪次（預設 128.0）
    float sigmaLum;     // 亮度邊緣停止 sigma（預設 4.0）
    uint  _pad0;
    uint  _pad1;
} pc;

// ─── 工具函式 ────────────────────────────────────────────────────────────────

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ─── 主入口 ─────────────────────────────────────────────────────────────────

void main() {
    ivec2 px = ivec2(gl_GlobalInvocationID.xy);
    if (px.x >= int(pc.width) || px.y >= int(pc.height)) return;

    vec4  centerColor  = imageLoad(u_inputColor, px);
    float centerDepth  = texelFetch(u_depthTex,  px, 0).r;
    vec3  rawNorm      = texelFetch(u_normalTex, px, 0).xyz;
    vec3  centerNormal = length(rawNorm) > 0.001 ? normalize(rawNorm) : vec3(0.0, 1.0, 0.0);
    float centerLum    = luma(centerColor.rgb);

    // ── 方差引導 sigma：高方差區域使用更寬濾波，低方差區域保留細節 ─────────
    vec2  mom         = imageLoad(u_moments, px).xy;
    float variance    = max(mom.y, 1e-6);
    // varFactor ∈ [0, 1]：高方差 → 接近 1.0（寬濾波）；低方差 → 接近 0.0（緊濾波）
    float varFactor   = 1.0 - exp(-variance * 16.0);
    float effectiveSigmaLum = pc.sigmaLum * (0.25 + 0.75 * varFactor);

    // ── 5×5 a-trous 核（B3 樣條：[1/16, 1/4, 3/8, 1/4, 1/16]）─────────────
    // 中心對稱索引 -2..+2 對應核索引 0,1,2,1,0
    const float kernel[3] = float[](3.0 / 8.0, 1.0 / 4.0, 1.0 / 16.0);

    vec4  acc  = vec4(0.0);
    float wSum = 0.0;

    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            ivec2 p = px + ivec2(dx, dy) * int(pc.stepSize);
            p = clamp(p,
                      ivec2(0, 0),
                      ivec2(int(pc.width) - 1, int(pc.height) - 1));

            vec4  sc = imageLoad(u_inputColor, p);
            float sd = texelFetch(u_depthTex,  p, 0).r;
            vec3  sn = texelFetch(u_normalTex, p, 0).xyz;
            sn = length(sn) > 0.001 ? normalize(sn) : vec3(0.0, 1.0, 0.0);

            // 邊緣停止函式
            float wDepth  = exp(-abs(centerDepth - sd) / (pc.sigmaDepth + 1e-6));
            float wNormal = pow(max(0.0, dot(centerNormal, sn)), pc.sigmaNormal);
            float wLum    = exp(-abs(centerLum - luma(sc.rgb)) / (effectiveSigmaLum + 1e-6));

            // 5×5 a-trous 核（中心為 abs 距離：0→3/8, 1→1/4, 2→1/16）
            float h = kernel[abs(dx)] * kernel[abs(dy)];
            float w = h * wDepth * wNormal * wLum;

            acc  += sc * w;
            wSum += w;
        }
    }

    imageStore(u_outputColor, px, acc / max(wSum, 1e-6));
}
