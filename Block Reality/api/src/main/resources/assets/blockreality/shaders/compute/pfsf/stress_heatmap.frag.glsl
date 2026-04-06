#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Stress Heatmap — 應力視覺化片段著色器
//  直接採樣 phi[] SSBO 產生熱力圖，零拷貝
//
//  v2.1 新增：d_field 相場損傷視覺化
//    d ∈ [0,1] 由 phase_field_evolve.comp.glsl 即時更新
//    d ∈ [0.85, 0.95] → smoothstep 插值顯示亞體素裂紋紋理
//    d > 0.95           → 深棕色裂縫，輕微透明（裂縫貫通）
//  參考：PFSF 手冊 §7.2
// ═══════════════════════════════════════════════════════════════

layout(location = 0) in flat uint voxelIndex;
layout(location = 1) in flat float voxelMaxPhi;

layout(location = 0) out vec4 fragColor;

layout(set = 1, binding = 0) readonly buffer StressBuf { float phi[];   };
layout(set = 1, binding = 1) readonly buffer DField    { float dField[]; }; // v2.1: 損傷相場

layout(push_constant) uniform PC {
    float time;  // 動畫時間（秒）
} pc;

void main() {
    float rawPhi = phi[voxelIndex];
    float maxPhiVal = max(voxelMaxPhi, 1.0); // 避免除零

    // Normalized stress: 0~2 range
    float stress = rawPhi / maxPhiVal;

    // ─── 色彩映射 ───
    // 冰藍（安全）→ 橙紅（臨界）→ 白（超載）
    vec3 color;

    vec3 icyBlue    = vec3(0.1, 0.3, 0.8);
    vec3 warningOrg = vec3(0.8, 0.5, 0.1);
    vec3 criticalRd = vec3(1.0, 0.34, 0.13);  // #FF5722
    vec3 overload   = vec3(1.0, 1.0, 1.0);

    if (stress < 0.5) {
        color = mix(icyBlue, warningOrg, stress * 2.0);
    } else if (stress < 1.0) {
        color = mix(warningOrg, criticalRd, (stress - 0.5) * 2.0);
    } else {
        color = mix(criticalRd, overload, min(stress - 1.0, 1.0));
    }

    // ─── 臨界斷裂橘脈衝動畫 (#FF5722) ───
    // stress > 0.85 時以 8Hz 頻率脈衝
    float pulse = 1.0;
    if (stress > 0.85) {
        pulse = 0.3 * sin(pc.time * 8.0 * 3.14159265) + 0.7;
    }

    // ─── v2.1: 相場損傷裂縫視覺化 ───
    // d_field 由 phase_field_evolve.comp.glsl（Ambati 2015）計算：
    //   d ∈ [0, 0.85)  → 純應力熱力圖（無裂縫顯示）
    //   d ∈ [0.85, 0.95] → smoothstep 漸進深棕色（裂縫萌生）
    //   d ∈ (0.95, 1.0]  → 完全裂縫色（0.3 alpha 衰減，視覺貫通感）
    //
    // 深棕色裂縫基色：vec3(0.05, 0.02, 0.0) ≈ #0D0500
    // 混合方式：linear mix → 裂縫越深色越暗，增強視覺反差
    float d = dField[voxelIndex];
    float visual_crack = smoothstep(0.85, 0.95, d);  // 0.0（無損傷）→ 1.0（斷裂）

    if (visual_crack > 0.0) {
        vec3 crackColor = vec3(0.05, 0.02, 0.0);  // 深棕裂縫色
        color = mix(color, crackColor, visual_crack);
        // 裂縫貫通時輕微透明（最多降低 30% alpha），增強視覺穿透感
        float alpha = 1.0 - visual_crack * 0.3;
        fragColor = vec4(color * pulse, alpha);
        return;
    }

    fragColor = vec4(color * pulse, 1.0);
}
