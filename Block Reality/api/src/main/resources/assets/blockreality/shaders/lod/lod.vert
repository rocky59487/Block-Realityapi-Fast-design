#version 450 core

// ─── 頂點屬性（interleaved, stride = 9 floats） ───────────────────────
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec3 a_Normal;
layout(location = 2) in vec2 a_UV;
layout(location = 3) in float a_MaterialId;

// ─── Uniforms ────────────────────────────────────────────────────────
uniform mat4 u_Projection;
uniform mat4 u_View;
uniform vec3 u_SectionOffset;   // section 世界空間偏移（sectionX*16, sectionY*16, sectionZ*16）
uniform int  u_LODLevel;        // 0-3（供 fragment shader 差異化渲染）

// ─── 輸出至 fragment shader ──────────────────────────────────────────
out vec3 v_WorldPos;
out vec3 v_Normal;
out vec2 v_UV;
flat out int v_MaterialId;
flat out int v_LODLevel;

void main() {
    vec3 worldPos = a_Position + u_SectionOffset;
    gl_Position   = u_Projection * u_View * vec4(worldPos, 1.0);

    v_WorldPos   = worldPos;
    v_Normal     = a_Normal;   // Section-space normal（section 無旋轉，直接傳遞）
    v_UV         = a_UV;
    v_MaterialId = int(a_MaterialId + 0.5);  // float→int 安全轉換
    v_LODLevel   = u_LODLevel;
}
