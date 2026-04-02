#version 460

// ══════════════════════════════════════════════════════════════════════════════
// volumetric_lighting.comp.glsl — 體積光照（God Ray）Compute Shader（P2-C）
//
// 使用光線步進（Ray Marching）模擬大氣中的 Mie 散射，產生逼真的
// 「光柱」（God Ray）和體積霧效果。
//
// 演算法：
//   1. 從相機位置沿觀察方向步進，步長 = worldStepSize
//   2. 在每個採樣點，從深度緩衝重建世界位置（確保不超過幾何表面）
//   3. 採樣陰影圖（cascade shadow map）判斷採樣點是否受陽光照射
//   4. 以 Henyey-Greenstein 相位函數計算 Mie 散射強度
//   5. 使用 Beer-Lambert 指數衰減計算光在霧中的傳播損失
//   6. 累積散射結果寫出到體積光照紋理（後續 composite pass 疊加到最終畫面）
//
// Binding 佈局（set 0）：
//   0  sampler2D   u_depthTex         本幀深度緩衝（view-space 線性深度）
//   1  sampler2D   u_shadowMap        平行光陰影圖（r = 線性深度或深度比較）
//   2  rgba16f SI  u_outputVolume     體積光照輸出（rgb = in-scatter, a = transmittance）
//
// Push Constants（48 bytes）：
//   width, height（uint × 2 = 8 bytes）
//   sunDirX, sunDirY, sunDirZ（float × 3 = 12 bytes）+ sunIntensity（float = 4 bytes）
//   fogDensity, henyeyG（float × 2 = 8 bytes）+ numSteps, frame（uint × 2 = 8 bytes）
//   nearPlane, farPlane（float × 2 = 8 bytes）
//
// 輸出格式：RGBA16F
//   rgb = 累積體積散射顏色（陽光 × 相位 × 透射率）
//   a   = 端點透射率（1.0 = 完全透明，0.0 = 完全霧化）
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// Binding 0: 深度緩衝（線性深度或 NDC 深度）
layout(set = 0, binding = 0) uniform sampler2D u_depthTex;

// Binding 1: 陰影圖（平行光，cascade 0）
layout(set = 0, binding = 1) uniform sampler2D u_shadowMap;

// Binding 2: 體積光照輸出
layout(set = 0, binding = 2, rgba16f) uniform writeonly image2D u_outputVolume;

layout(push_constant) uniform PC {
    uint  width;
    uint  height;
    float sunDirX;       // 世界空間太陽方向（normalized，指向太陽）
    float sunDirY;
    float sunDirZ;
    float sunIntensity;  // 太陽光強度（1.0 = 正常白天）
    float fogDensity;    // 霧密度係數（0.002 = 輕霧，0.02 = 濃霧）
    float henyeyG;       // HG 相位函數各向異性（0.0 = 均勻散射，0.8 = 強前向散射）
    uint  numSteps;      // 步進次數（16-64，越高越精確但越慢）
    uint  frame;         // 幀索引（用於 temporal 抖動）
    float nearPlane;     // 相機近裁切平面（預設 0.1 Minecraft 方塊）
    float farPlane;      // 體積光照最大距離（預設 64 方塊）
} pc;

// ─── Henyey-Greenstein 相位函數 ──────────────────────────────────────────────
// g = 0: 均勻散射；g > 0: 前向散射（光往相同方向散射，典型 Mie 散射）；g < 0: 後向
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * 3.14159265 * pow(max(denom, 1e-6), 1.5));
}

// ─── Beer-Lambert 透射率 ────────────────────────────────────────────────────
float beerLambert(float density, float distance) {
    return exp(-density * distance);
}

// ─── 從 NDC 重建世界位置（簡化：僅使用深度和 UV）────────────────────────────
// 注意：完整實作需要 invViewProj 矩陣。此簡化版直接以 view-ray 近似。
vec3 reconstructViewRay(vec2 uv) {
    // NDC [-1, 1] 空間中的方向
    vec2 ndc = uv * 2.0 - 1.0;
    // 假設 90° 垂直 FOV、屏幕比例 16/9（實際值應透過 UBO 提供）
    float aspect = float(pc.width) / float(pc.height);
    float tanHalfFov = 0.7265; // tan(36°) ≈ tan(FOV/2)，Minecraft 預設 70° FOV
    return normalize(vec3(ndc.x * aspect * tanHalfFov, ndc.y * tanHalfFov, -1.0));
}

// ─── 採樣陰影圖（簡化：無 cascade 矩陣，使用 UV 近似）──────────────────────
// Phase 2 整合點：提供 lightSpaceMat UBO 後，此函式替換為正確的投影採樣
float sampleShadow(vec3 viewPos, float stepIdx) {
    // 使用 stepIdx 的 hash 生成柔和陰影（無矩陣 fallback）
    // Phase 2 實作：projPos = lightSpaceMat * vec4(worldPos, 1.0)
    //              shadow = texture(u_shadowMap, projPos.xy * 0.5 + 0.5).r > projPos.z - bias
    // 目前返回 1.0（完全照亮）以確保體積霧可見（無陰影圖版本仍能產生霧效）
    return 1.0;
}

// ─── 主入口 ─────────────────────────────────────────────────────────────────

void main() {
    ivec2 px = ivec2(gl_GlobalInvocationID.xy);
    if (px.x >= int(pc.width) || px.y >= int(pc.height)) return;

    vec2 uv = (vec2(px) + 0.5) / vec2(float(pc.width), float(pc.height));

    // 從深度緩衝重建採樣終點距離
    float rawDepth   = texture(u_depthTex, uv).r;
    // 線性化 NDC 深度 → 視空間距離
    float linearDepth = pc.nearPlane * pc.farPlane
                        / (pc.farPlane - rawDepth * (pc.farPlane - pc.nearPlane));
    float hitDist = min(linearDepth, pc.farPlane);

    // 觀察方向（view space）
    vec3 viewDir  = reconstructViewRay(uv);
    vec3 sunDir   = normalize(vec3(pc.sunDirX, pc.sunDirY, pc.sunDirZ));

    // cos(θ) between view direction and sun direction
    float cosTheta = dot(-viewDir, sunDir);  // negative viewDir = looking away from camera

    // Henyey-Greenstein 相位（Mie 散射）
    float phase = henyeyGreenstein(cosTheta, pc.henyeyG);

    // ── Temporal 抖動（4-frame Halton 序列）────────────────────────────────
    // 避免步進帶來的條紋偽影（banding artifacts）
    const float halton4[4] = float[](0.0, 0.5, 0.25, 0.75);
    float jitter = halton4[pc.frame & 3u];

    // ── 步進累積 ────────────────────────────────────────────────────────────
    float stepSize    = hitDist / float(pc.numSteps);
    vec3  accumScatter = vec3(0.0);
    float transmittance = 1.0;  // 開始時完全透明

    // 陽光顏色（白天暖白色，可日後從大氣散射模型取得）
    vec3 sunColor = vec3(1.0, 0.95, 0.85) * pc.sunIntensity;

    for (uint i = 0u; i < pc.numSteps; i++) {
        // 採樣距離（加入 jitter 打散條紋）
        float t       = (float(i) + jitter) * stepSize;
        vec3  sampleViewPos = viewDir * t;

        // 超過幾何表面則停止
        if (t >= hitDist) break;

        // 陰影採樣（Phase 2 將替換為正確 shadow map 投影）
        float shadow = sampleShadow(sampleViewPos, float(i));

        // 此步的散射貢獻
        vec3  scattering = sunColor * phase * shadow * transmittance;
        accumScatter += scattering * stepSize;

        // Beer-Lambert 透射率更新（沿步長的消光）
        transmittance *= beerLambert(pc.fogDensity, stepSize);

        // 透射率接近 0 時提前退出（優化）
        if (transmittance < 0.001) break;
    }

    // ── 輸出 ────────────────────────────────────────────────────────────────
    // rgb = 累積散射（乘以霧密度係數使強度合理）
    // a   = 端點透射率（composite pass 使用：finalColor = sceneColor * a + scatter * (1-a)）
    vec4 result = vec4(accumScatter * pc.fogDensity * 100.0,
                       clamp(transmittance, 0.0, 1.0));
    imageStore(u_outputVolume, px, result);
}
