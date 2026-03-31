#version 450 core

// ─── 輸入 ─────────────────────────────────────────────────────────────
in vec3 v_WorldPos;
in vec3 v_Normal;
in vec2 v_UV;
flat in int v_MaterialId;
flat in int v_LODLevel;

// ─── Uniforms ────────────────────────────────────────────────────────
uniform sampler2DArray u_BlockAtlas;    // 方塊紋理陣列（layer = materialId）
uniform vec3  u_SunDir;                 // 正規化太陽方向
uniform vec3  u_SunColor;              // 太陽顏色
uniform vec3  u_AmbientColor;          // 環境光顏色
uniform float u_FogStart;
uniform float u_FogEnd;
uniform vec3  u_FogColor;
uniform vec3  u_CameraPos;
uniform float u_Time;                  // 動畫時間（秒）

// ─── GBuffer 輸出（deferred rendering） ─────────────────────────────
layout(location = 0) out vec4 g_Albedo;    // RGB = albedo, A = AO
layout(location = 1) out vec4 g_Normal;    // RGB = world normal (encoded)
layout(location = 2) out vec4 g_Material;  // R = roughness, G = metallic, B = matId, A = LOD

// ─── 法線編碼（octahedron normal encoding） ──────────────────────────
vec2 encodeNormal(vec3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
    }
    return n.xy * 0.5 + 0.5;
}

// ─── 材料屬性查詢（簡化版，未來由材料 UBO 取代） ──────────────────────
struct MaterialProps {
    float roughness;
    float metallic;
    float emission;
};

MaterialProps getMaterialProps(int matId) {
    // 基本預設值，依 matId 差異化
    float roughness = 0.8 - float(matId % 5) * 0.1;
    float metallic  = float(matId % 8 == 0 ? 1 : 0) * 0.9;
    float emission  = float(matId == 89 || matId == 169) * 1.5; // 螢光石、海晶燈
    return MaterialProps(
        clamp(roughness, 0.1, 1.0),
        clamp(metallic,  0.0, 1.0),
        emission
    );
}

// ─── LOD 混合（高 LOD 使用程序化紋理減少紋素化） ────────────────────
vec4 sampleWithLOD(vec2 uv, int matId, int lod) {
    vec4 texColor = texture(u_BlockAtlas, vec3(uv, float(matId)));

    // LOD 3：遠距離使用平均顏色 + 程序化噪聲
    if (lod >= 3) {
        float noise = fract(sin(dot(v_WorldPos.xz, vec2(127.1, 311.7))) * 43758.5453);
        texColor.rgb = mix(texColor.rgb, texColor.rgb * (0.9 + noise * 0.2), 0.5);
    }
    return texColor;
}

// ─── 霧效 ─────────────────────────────────────────────────────────────
vec3 applyFog(vec3 color, float dist) {
    float fogFactor = clamp((dist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
    return mix(color, u_FogColor, fogFactor);
}

void main() {
    // 材料屬性
    MaterialProps mat = getMaterialProps(v_MaterialId);

    // 紋理採樣
    vec4 albedo = sampleWithLOD(v_UV, v_MaterialId, v_LODLevel);
    if (albedo.a < 0.1) discard; // alpha cutout

    // 頂點法線（需正規化，插值後不保證單位長度）
    vec3 N = normalize(v_Normal);

    // 簡單朗伯漫反射（deferred 中最終光照由 deferred light pass 計算）
    float NdotL = max(dot(N, normalize(u_SunDir)), 0.0);
    vec3 diffuse = albedo.rgb * (u_AmbientColor + u_SunColor * NdotL * 0.8);

    // 霧效（forward fallback，deferred pass 覆蓋）
    float distToCam = length(v_WorldPos - u_CameraPos);
    diffuse = applyFog(diffuse, distToCam);

    // 發光材料
    diffuse += albedo.rgb * mat.emission;

    // GBuffer 輸出
    g_Albedo   = vec4(albedo.rgb, 1.0);
    g_Normal   = vec4(encodeNormal(N), 0.0, 1.0);
    g_Material = vec4(mat.roughness, mat.metallic,
                      float(v_MaterialId) / 255.0,
                      float(v_LODLevel) / 3.0);
}
