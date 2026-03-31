#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : enable
#extension GL_EXT_scalar_block_layout : enable

// ─── Bindings ───────────────────────────────────────────────────────
layout(set = 0, binding = 0) uniform accelerationStructureEXT u_TLAS;
layout(set = 0, binding = 1, rgba16f) uniform image2D u_RTOutput;    // RT result image

// GBuffer (from OpenGL LOD pass via interop)
layout(set = 1, binding = 0) uniform sampler2D g_Depth;
layout(set = 1, binding = 1) uniform sampler2D g_Normal;   // octahedron encoded
layout(set = 1, binding = 2) uniform sampler2D g_Albedo;
layout(set = 1, binding = 3) uniform sampler2D g_Material; // roughness, metallic, matId, lod

// Camera UBO
layout(set = 2, binding = 0) uniform CameraData {
    mat4 invViewProj;
    vec3 camPos;
    float _pad0;
    vec3 sunDir;
    float _pad1;
    vec3 sunColor;
    float _pad2;
} cam;

// ─── Payload ────────────────────────────────────────────────────────
layout(location = 0) rayPayloadEXT vec4 payload;  // RGB = radiance, A = shadow factor

// ─── 法線解碼（octahedron） ──────────────────────────────────────────
vec3 decodeNormal(vec2 encoded) {
    vec2 f = encoded * 2.0 - 1.0;
    vec3 n = vec3(f.x, f.y, 1.0 - abs(f.x) - abs(f.y));
    float t = clamp(-n.z, 0.0, 1.0);
    n.xy += mix(vec2(-t, -t), vec2(t, t), step(0.0, n.xy));
    return normalize(n);
}

// ─── 重建世界空間位置（從深度） ──────────────────────────────────────
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = cam.invViewProj * clip;
    return world.xyz / world.w;
}

// ─── RT Shadows：per-pixel shadow ray ───────────────────────────────
float traceShadowRay(vec3 worldPos, vec3 normal) {
    vec3 origin    = worldPos + normal * 0.005;  // bias 避免 self-intersection
    vec3 direction = normalize(cam.sunDir);
    float tMin = 0.001;
    float tMax = 10000.0;

    payload = vec4(1.0); // default: in light

    traceRayEXT(
        u_TLAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
        0xFF,       // cullMask
        0,          // sbtRecordOffset
        0,          // sbtRecordStride
        1,          // missIndex (shadow miss)
        origin,
        tMin,
        direction,
        tMax,
        0           // payload location
    );

    return payload.a; // 1.0 = in light, 0.0 = shadowed
}

// ─── RT Reflections：per-pixel reflection ray ──────────────────────
vec3 traceReflectionRay(vec3 worldPos, vec3 normal, vec3 viewDir, float roughness) {
    if (roughness > 0.8) return vec3(0.0); // 粗糙表面跳過

    vec3 reflDir = reflect(viewDir, normal);
    vec3 origin  = worldPos + normal * 0.01;

    payload = vec4(0.0);

    traceRayEXT(
        u_TLAS,
        gl_RayFlagsOpaqueEXT,
        0xFF,
        0,   // sbtRecordOffset (closest hit)
        0,
        0,   // missIndex (sky miss)
        origin,
        0.001,
        reflDir,
        1000.0,
        0
    );

    return payload.rgb;
}

// ─── 主程式 ───────────────────────────────────────────────────────────
void main() {
    ivec2 coord = ivec2(gl_LaunchIDEXT.xy);
    vec2  uv    = (vec2(coord) + 0.5) / vec2(gl_LaunchSizeEXT.xy);

    // 讀取 GBuffer
    float depth    = texture(g_Depth, uv).r;
    vec4  normalXY = texture(g_Normal, uv);
    vec4  albedo   = texture(g_Albedo, uv);
    vec4  material = texture(g_Material, uv);

    // 背景（sky）：深度為 1.0
    if (depth >= 1.0) {
        imageStore(u_RTOutput, coord, vec4(0.0));
        return;
    }

    // 解碼 GBuffer
    vec3 worldPos  = reconstructWorldPos(uv, depth);
    vec3 normal    = decodeNormal(normalXY.xy);
    float roughness = material.r;
    float metallic  = material.g;

    vec3 viewDir = normalize(cam.camPos - worldPos);

    // ── RT Shadows ───────────────────────────────────────────────────
    float shadow = traceShadowRay(worldPos, normal);

    // ── RT Reflections ───────────────────────────────────────────────
    vec3 reflection = traceReflectionRay(worldPos, normal, -viewDir, roughness);

    // ── 合成輸出 ─────────────────────────────────────────────────────
    // RT output = shadow factor (R) + reflection (GBA)
    // BRSVGFDenoiser 在此輸出基礎上進行時空降噪
    vec4 rtResult = vec4(shadow, reflection);
    imageStore(u_RTOutput, coord, rtResult);
}
