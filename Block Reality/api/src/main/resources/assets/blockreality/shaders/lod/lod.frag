#version 150 core
// Block Reality LOD Fragment Shader
// 移植來源：Voxy lod.frag（移除 Sodium 相依，加入 Block Reality 應力視覺化接口）

in vec4  v_Color;
in vec3  v_Normal;
in vec3  v_WorldPos;
in float v_FogFactor;

out vec4 FragColor;

// ─── Uniforms ───

// 環境光 / 方向光
uniform vec3  u_SunDirection;    // 正規化太陽方向（世界空間）
uniform vec3  u_SunColor;        // 太陽光顏色
uniform float u_SunIntensity;    // 太陽光強度（0.0–2.0）
uniform vec3  u_AmbientColor;    // 環境光顏色

// 霧色（與 vert shader 的 v_FogFactor 搭配）
uniform vec4 u_FogColor;

// LOD 偵錯模式（1 = 顯示 LOD 等級色彩覆蓋）
uniform int u_DebugLodLevel;     // 0=無, 1=LOD-1, 2=LOD-2, 3=LOD-3

// 應力視覺化（Phase 4 接入，目前預留）
// uniform sampler2D u_StressMap;
// uniform float u_StressVizStrength;

void main() {
    vec3 normal = normalize(v_Normal);
    vec3 baseColor = v_Color.rgb;

    // ─── Lambert 漫反射光照 ───
    float NdotL   = max(dot(normal, normalize(u_SunDirection)), 0.0);
    vec3 diffuse  = u_SunColor * u_SunIntensity * NdotL;
    vec3 ambient  = u_AmbientColor;

    vec3 litColor = baseColor * (ambient + diffuse);

    // ─── LOD 偵錯色彩覆蓋 ───
    if (u_DebugLodLevel > 0) {
        vec3 lodColor;
        if      (u_DebugLodLevel == 1) lodColor = vec3(0.0, 0.8, 0.2); // 綠 = LOD-1
        else if (u_DebugLodLevel == 2) lodColor = vec3(1.0, 0.7, 0.0); // 橙 = LOD-2
        else                            lodColor = vec3(0.8, 0.0, 0.0); // 紅 = LOD-3
        litColor = mix(litColor, lodColor, 0.4);
    }

    // ─── 霧效混合 ───
    vec3 finalColor = mix(u_FogColor.rgb, litColor, v_FogFactor);

    FragColor = vec4(finalColor, v_Color.a);
}
