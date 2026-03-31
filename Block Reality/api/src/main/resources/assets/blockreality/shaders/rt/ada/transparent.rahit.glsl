#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Ada/Blackwell Any-Hit Shader（透明方塊）
//  處理玻璃、水、葉片等半透明材料的 alpha test
//  搭配 Opacity Micromap (OMM) 使用，大幅減少 any-hit 調用次數
// ═══════════════════════════════════════════════════════════════════════════

#extension GL_EXT_ray_tracing          : require
#extension GL_EXT_scalar_block_layout  : require
#extension GL_NV_shader_execution_reordering : require

// ─── Payload ─────────────────────────────────────────────────────────────
layout(location = 0) rayPayloadInEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} payload;

hitAttributeEXT vec2 baryCoord;

// ─── 材料透明度查詢 ────────────────────────────────────────────────────────
struct MatTransparency {
    float alpha;         // 0=不透明, 1=完全透明
    float ior;
    float _pad[2];
};
layout(set = 3, binding = 4, scalar) readonly buffer TransparencyBuffer {
    MatTransparency data[256];
} transBuf;

// ─── 折射顏色（水、有色玻璃） ──────────────────────────────────────────────
struct RefractionColor {
    vec3  tint;
    float _p;
};
layout(set = 3, binding = 5, scalar) readonly buffer RefractionBuffer {
    RefractionColor data[256];
} refrBuf;

void main() {
    uint customIdx = gl_InstanceCustomIndexEXT;
    uint matId     = clamp(customIdx & 0xFFFFu, 0u, 255u);

    float alpha = transBuf.data[matId].alpha;

    if (alpha < 0.05) {
        // 完全透明 → 跳過此命中，射線繼續
        ignoreIntersectionEXT;
        return;
    }

    if (alpha < 0.95) {
        // 半透明：用 bary + matId 做隨機 alpha test
        // 低差異序列（Van der Corput）讓 1 spp 半透明更一致
        uint seed = gl_PrimitiveID * 2654435761u ^ gl_InstanceID * 1234567u
                    ^ floatBitsToUint(baryCoord.x * 1000.0);
        seed ^= (seed >> 16u); seed *= 0x45d9f3bu; seed ^= (seed >> 16u);
        float rand = float(seed & 0xFFFFu) / 65535.0;

        if (rand > alpha) {
            // 機率性透過
            ignoreIntersectionEXT;
            return;
        }

        // 透過時：將折射顏色疊加到 payload（次級 refraction ray 在 Phase 3 實作）
        vec3 tint = refrBuf.data[matId].tint;
        payload.radiance *= tint; // 衰減穿透顏色
        // 繼續傳遞（允許 closest hit 執行）
    }

    // alpha >= 0.95：不透明，正常執行 closest hit
}
