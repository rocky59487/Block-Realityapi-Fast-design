#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : enable

// ─── Payload ────────────────────────────────────────────────────────
layout(location = 0) rayPayloadInEXT vec4 payload;

// ─── Hit attributes ─────────────────────────────────────────────────
hitAttributeEXT vec2 hitAttrib;

// ─── Bindings ────────────────────────────────────────────────────────
layout(set = 2, binding = 0) uniform CameraData {
    mat4 invViewProj;
    vec3 camPos;
    float _pad0;
    vec3 sunDir;
    float _pad1;
    vec3 sunColor;
    float _pad2;
} cam;

void main() {
    // 反射光線命中地形：回傳基於法線的簡單光照色
    // 完整材料系統在 Phase 3 GI 實作時整合

    vec3 normal  = vec3(0.0, 1.0, 0.0);  // 近似法線（Phase 3 改為從 VBO 讀取）
    float NdotL  = max(dot(normal, normalize(cam.sunDir)), 0.0);
    vec3 albedo  = vec3(0.5, 0.5, 0.5);  // 預設灰色
    vec3 diffuse = albedo * (cam.sunColor * NdotL + vec3(0.1));

    payload = vec4(diffuse, 0.0); // A=0 表示已命中
}
