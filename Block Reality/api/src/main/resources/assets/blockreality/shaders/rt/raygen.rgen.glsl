/*
 * Block Reality — Ray Generation Shader（Phase 3）
 *
 * 完整實作：
 *   - 逆投影矩陣重建射線
 *   - 主射線 → closesthit / miss
 *   - 輸出到 outputImage（RGBA16F storage image）
 *
 * Phase 4 預留：TAA 抖動、denoise pass 輸入
 */
#version 460
#extension GL_EXT_ray_tracing : require

// ─── Descriptor Set 0 ───
layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
layout(set = 0, binding = 1, rgba16f) uniform image2D outputImage;

layout(set = 0, binding = 2) uniform CameraUBO {
    mat4 viewInverse;
    mat4 projInverse;
    vec4 cameraPos;     // xyz = world position
    float time;         // seconds
    float debugMode;    // 0=off, 1=stress heatmap only, 2=normal viz
    float _pad0;
    float _pad1;
} camera;

// ─── Payload ───
layout(location = 0) rayPayloadEXT vec4 hitPayload;

void main() {
    const uvec2 pixel   = gl_LaunchIDEXT.xy;
    const uvec2 imgSize = gl_LaunchSizeEXT.xy;

    // NDC [-1,1] (Y flipped for Vulkan)
    const vec2 uv  = (vec2(pixel) + 0.5) / vec2(imgSize);
    const vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);

    // 逆投影：view-space ray target
    vec4 targetVS = camera.projInverse * vec4(ndc, 1.0, 1.0);
    targetVS.xyz  = normalize(targetVS.xyz);

    // 世界空間射線
    vec3 rayDir    = normalize(vec3(camera.viewInverse * vec4(targetVS.xyz, 0.0)));
    vec3 rayOrigin = camera.cameraPos.xyz;

    // 初始 payload = sky blue（miss shader 覆蓋）
    hitPayload = vec4(0.4, 0.6, 1.0, 1.0);

    traceRayEXT(
        topLevelAS,
        gl_RayFlagsOpaqueEXT,
        0xFF,    // all instances
        0,       // sbtRecordOffset
        0,       // sbtRecordStride
        0,       // missIndex = primary miss
        rayOrigin,
        0.001,
        rayDir,
        10000.0,
        0        // payload location
    );

    imageStore(outputImage, ivec2(pixel), hitPayload);
}
