#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Stress Heatmap — 應力視覺化片段著色器
//  直接採樣 phi[] SSBO 產生熱力圖，零拷貝
//  參考：PFSF 手冊 §7.2
// ═══════════════════════════════════════════════════════════════

layout(location = 0) in flat uint voxelIndex;
layout(location = 1) in flat float voxelMaxPhi;

layout(location = 0) out vec4 fragColor;

layout(set = 1, binding = 0) readonly buffer StressBuf { float phi[]; };

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

    fragColor = vec4(color * pulse, 1.0);
}
