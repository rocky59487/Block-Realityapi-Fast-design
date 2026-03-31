#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Shadow Miss Shader
//  陰影射線未命中 → 像素在光照中
// ═══════════════════════════════════════════════════════════════════════════
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT float shadowPayload;

void main() {
    shadowPayload = 1.0; // in light
}
