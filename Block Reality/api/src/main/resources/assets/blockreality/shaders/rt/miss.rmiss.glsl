#version 460
#extension GL_EXT_ray_tracing : require

// ─── Payload ────────────────────────────────────────────────────────
layout(location = 0) rayPayloadInEXT vec4 payload;

// ─── Sky color（簡化大氣散射） ────────────────────────────────────────
// Preetham 大氣模型的簡化版，未來由 BRAtmosphereEngine 的 LUT 替代。

layout(set = 2, binding = 0) uniform CameraData {
    mat4 invViewProj;
    vec3 camPos;
    float _pad0;
    vec3 sunDir;
    float _pad1;
    vec3 sunColor;
    float _pad2;
} cam;

// Rayleigh + Mie 近似
vec3 atmosphericScatter(vec3 rayDir) {
    float sunDot = max(dot(rayDir, normalize(cam.sunDir)), 0.0);

    // 天空藍（Rayleigh）
    vec3 rayleigh = mix(
        vec3(0.1, 0.2, 0.5),   // zenith
        vec3(0.5, 0.7, 1.0),   // horizon
        pow(1.0 - max(rayDir.y, 0.0), 3.0)
    );

    // 太陽暈（Mie）
    float mie = pow(sunDot, 64.0) * 2.0;
    vec3 sunGlow = cam.sunColor * mie;

    // 地平線橙黃（日落）
    float horizonFactor = pow(1.0 - abs(rayDir.y), 8.0);
    vec3 horizon = vec3(1.0, 0.5, 0.2) * horizonFactor * max(dot(normalize(cam.sunDir), vec3(1,0,0)), 0.0);

    return rayleigh + sunGlow + horizon;
}

void main() {
    vec3 rayDir = gl_WorldRayDirectionEXT;

    // Shadow miss shader（location 1）：光線未被遮擋 → in light
    // 兩個 miss shader 共用同一個 payload location，透過 sbtIndex 區分。
    // 此處：通用 miss → 回傳天空色（反射 miss），或 shadow=1（shadow miss）

    if (rayDir.y < 0.0) {
        // 向下的光線未命中 → 地面預設色
        payload = vec4(0.05, 0.05, 0.05, 1.0);
    } else {
        // 向上的光線 → 天空色（用於反射）
        vec3 sky = atmosphericScatter(normalize(rayDir));
        payload = vec4(sky, 1.0); // A=1 表示 in light（shadow miss 也使用此 shader）
    }
}
