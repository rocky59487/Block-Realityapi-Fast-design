#version 460

// ══════════════════════════════════════════════════════════════════════════════
// relax_temporal.comp.glsl — ReLAX-style 時間累積降噪（P2-A）
//
// 替換 BRSVGFDenoiser 的 OpenGL 時間通道，改以純 Vulkan compute 實作：
//
// 改進項目（相較 SVGF GL 版本）：
//   ✓ 純 Vulkan compute（無 GL 依賴，與 RT 管線共享命令緩衝區）
//   ✓ 亮度 AABB 鉗制（3×3 鄰域，YCoCg-like 快速版）— 消除鬼影
//   ✓ 基於深度一致性的歷史有效性判斷（無需相機矩陣）
//   ✓ Welford EMA 方差追蹤（供後續 a-trous 方差引導銳化使用）
//
// Pass 順序：
//   relax_temporal（本 shader）→ relax_atrous × N 次
//
// Binding 佈局（set 0）：
//   0  rgba16f STORAGE_IMAGE  currentRT（本幀 RT 輸出，只讀）
//   1  rgba16f STORAGE_IMAGE  historyAccum（上一幀累積結果，只讀）
//   2  rgba16f STORAGE_IMAGE  outputAccum（寫出累積結果）
//   3  rg32f   STORAGE_IMAGE  prevMoments（上一幀 [mean, variance]，只讀）
//   4  rg32f   STORAGE_IMAGE  outputMoments（寫出 [mean, variance]）
//   5  sampler2D              depthTex（本幀深度）
//   6  sampler2D              prevDepthTex（上一幀深度）
//   7  sampler2D              normalTex（本幀法線 RGB = oct-normal 或 view-space XYZ）
//
// Push Constants（16 bytes）：
//   width, height, alpha（混合係數，0.1 = 90% 歷史），frameIndex
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0, rgba16f) uniform readonly  image2D u_currentRT;
layout(set = 0, binding = 1, rgba16f) uniform readonly  image2D u_historyAccum;
layout(set = 0, binding = 2, rgba16f) uniform writeonly image2D u_outputAccum;
layout(set = 0, binding = 3, rg32f)   uniform readonly  image2D u_prevMoments;
layout(set = 0, binding = 4, rg32f)   uniform writeonly image2D u_outputMoments;
layout(set = 0, binding = 5) uniform sampler2D u_depthTex;
layout(set = 0, binding = 6) uniform sampler2D u_prevDepthTex;
layout(set = 0, binding = 7) uniform sampler2D u_normalTex;

layout(push_constant) uniform PC {
    uint  width;
    uint  height;
    float alpha;       // 時間混合比例：0.1 = 10% 本幀 + 90% 歷史
    uint  frameIndex;
} pc;

// ─── 工具函式 ────────────────────────────────────────────────────────────────

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ─── 主入口 ─────────────────────────────────────────────────────────────────

void main() {
    ivec2 px = ivec2(gl_GlobalInvocationID.xy);
    if (px.x >= int(pc.width) || px.y >= int(pc.height)) return;

    vec4 current = imageLoad(u_currentRT, px);

    // ── 深度一致性驗證（歷史有效性判斷）─────────────────────────────────────
    vec2  uv       = (vec2(px) + 0.5) / vec2(float(pc.width), float(pc.height));
    float curDepth = texture(u_depthTex, uv).r;
    float prvDepth = texture(u_prevDepthTex, uv).r;

    // 深度差異 > 閾值或到達遠平面（天空）時拒絕歷史
    bool  valid    = (abs(curDepth - prvDepth) < 0.01) && (curDepth < 0.9999);

    // ── 3×3 鄰域亮度 AABB（快速鬼影抑制）───────────────────────────────────
    vec3 lumMin = current.rgb;
    vec3 lumMax = current.rgb;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            ivec2 p = clamp(px + ivec2(dx, dy),
                            ivec2(0, 0),
                            ivec2(int(pc.width) - 1, int(pc.height) - 1));
            vec3 n = imageLoad(u_currentRT, p).rgb;
            lumMin = min(lumMin, n);
            lumMax = max(lumMax, n);
        }
    }

    // ── 時間混合 ────────────────────────────────────────────────────────────
    float blendAlpha = valid ? pc.alpha : 1.0;

    vec4 history        = imageLoad(u_historyAccum, px);
    // 將歷史鉗制到當前鄰域 AABB — 消除時間鬼影
    vec3 clampedHistory = clamp(history.rgb, lumMin, lumMax);

    vec4 result = vec4(mix(clampedHistory, current.rgb, blendAlpha), current.a);
    imageStore(u_outputAccum, px, result);

    // ── 方差追蹤（Welford EMA，供 a-trous 方差引導使用）────────────────────
    vec2  prevMom  = imageLoad(u_prevMoments, px).xy;
    float curLum   = luma(current.rgb);

    // EMA 更新：alpha 越大 → 歷史記憶越短
    float newMean  = mix(prevMom.x, curLum, blendAlpha);
    float diff     = curLum - newMean;
    float newVar   = mix(prevMom.y, diff * diff, blendAlpha);

    imageStore(u_outputMoments, px, vec2(newMean, newVar));
}
