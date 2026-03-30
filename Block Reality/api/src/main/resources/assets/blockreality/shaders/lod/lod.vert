#version 150 core
// Block Reality LOD Vertex Shader
// 移植來源：Voxy lod.vert（已移除 Sodium #ifdef 分支）
// 與 LODTerrainBuffer 的頂點格式對應：
//   location=0: position (xyz, 3 floats)
//   location=1: normal   (xyz, 3 floats)
//   location=2: color    (rgba, 4 floats)

in vec3 a_Position;
in vec3 a_Normal;
in vec4 a_Color;

out vec4 v_Color;
out vec3 v_Normal;
out vec3 v_WorldPos;
out float v_FogFactor;

// ─── Uniforms ───

// MVP 矩陣（由 LODRenderDispatcher 每幀更新）
uniform mat4 u_ModelViewProj;
uniform mat4 u_ModelView;

// 霧效參數（對應 Minecraft 原生霧）
uniform float u_FogStart;
uniform float u_FogEnd;
uniform vec4  u_FogColor;

// LOD 遠距淡入（避免突然出現，配合 Voxy 的 fadeIn）
uniform float u_LodFadeDistance; // 開始淡入的距離（chunk 單位）
uniform vec3  u_CameraPos;

void main() {
    vec4 worldPos4 = vec4(a_Position, 1.0);
    gl_Position = u_ModelViewProj * worldPos4;

    v_Color    = a_Color;
    v_Normal   = a_Normal;
    v_WorldPos = a_Position;

    // ─── 線性霧計算 ───
    float eyeDist = length((u_ModelView * worldPos4).xyz);
    v_FogFactor = clamp((u_FogEnd - eyeDist) / (u_FogEnd - u_FogStart), 0.0, 1.0);
}
