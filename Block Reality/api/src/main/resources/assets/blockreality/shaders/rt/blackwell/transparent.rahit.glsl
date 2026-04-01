#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Blackwell Any-Hit Shader（透明方塊）
//  玻璃、水、葉片等半透明材料的 alpha test
//  搭配 Opacity Micromap (OMM) 使用（Phase 3+ 完整 OMM 整合）
//
//  與 Ada transparent.rahit.glsl 邏輯相同；
//  在 Blackwell 路徑中，不透明 section 使用 VK_GEOMETRY_OPAQUE_BIT_KHR
//  跳過 any-hit（由 BRClusterBVH 的 opaque flag 控制），
//  此 shader 僅對標記含透明方塊的 cluster/section 啟用
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_tracing                        : require
#extension GL_EXT_scalar_block_layout                : require
#extension GL_NV_shader_execution_reordering         : require
#extension GL_NV_cluster_acceleration_structure      : enable  // Cluster BVH

layout(location = 0) rayPayloadInEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} payload;

hitAttributeEXT vec2 baryCoord;

struct MatTransparency {
    float alpha;
    float ior;
    float _pad[2];
};
layout(set = 3, binding = 4, scalar) readonly buffer TransparencyBuffer {
    MatTransparency data[256];
} transBuf;

struct RefractionColor {
    vec3  tint;
    float _p;
};
layout(set = 3, binding = 5, scalar) readonly buffer RefractionBuffer {
    RefractionColor data[256];
} refrBuf;

void main() {
    // ★ Blackwell Cluster AS：customIndex 低 16 位 = matId（與 Ada 相同）
    uint customIdx = gl_InstanceCustomIndexEXT;
    uint matId     = clamp(customIdx & 0xFFFFu, 0u, 255u);
    float alpha    = transBuf.data[matId].alpha;

    if (alpha < 0.05) {
        ignoreIntersectionEXT;
        return;
    }

    if (alpha < 0.95) {
        uint seed = gl_PrimitiveID * 2654435761u ^ gl_InstanceID * 1234567u
                    ^ floatBitsToUint(baryCoord.x * 1000.0);
        seed ^= (seed >> 16u); seed *= 0x45d9f3bu; seed ^= (seed >> 16u);
        float rand = float(seed & 0xFFFFu) / 65535.0;

        if (rand > alpha) {
            ignoreIntersectionEXT;
            return;
        }

        vec3 tint = refrBuf.data[matId].tint;
        payload.radiance *= tint;
    }

    // alpha >= 0.95：不透明，正常執行 closest hit
}
