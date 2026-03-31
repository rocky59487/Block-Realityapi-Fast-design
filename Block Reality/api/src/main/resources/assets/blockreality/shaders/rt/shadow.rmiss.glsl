/*
 * Block Reality — Shadow Miss Shader（Phase 2-D）
 *
 * 陰影光線未碰撞任何遮擋體 → 不在陰影中。
 */
#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT bool isShadowed;

void main() {
    isShadowed = false;  // 光線抵達光源，不遮蔽
}
