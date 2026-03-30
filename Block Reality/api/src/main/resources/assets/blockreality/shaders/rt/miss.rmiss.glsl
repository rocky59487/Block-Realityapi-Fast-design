/*
 * Block Reality — Primary Miss Shader（Phase 2-D）
 *
 * 光線未碰撞任何幾何體時，回傳天空色。
 * Phase 3 升級為：sky procedural（Mie 散射 + Rayleigh）
 */
#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec4 hitPayload;

void main() {
    // 簡單天空藍
    hitPayload = vec4(0.4, 0.6, 1.0, 1.0);
}
