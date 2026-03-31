/*
 * Block Reality — Closest Hit Shader（Phase 3）
 *
 * 全功能實作：
 *   - Lambert 漫反射（法向量來自頂點插值）
 *   - 陰影光線（secondary ray → shadow.rmiss / shadow chit）
 *   - 1-bounce GI（hemisphere sample，隨機種子）
 *   - 應力熱圖疊加（StressVisualizationRT 數據，binding 3）
 *
 * Shader Storage Buffer 布局（set=0）：
 *   binding 3 = SSBO：VertexBuffer（與 BLAS 頂點相同）
 *   binding 4 = SSBO：StressBuffer（float[] 壓力值 per chunk）
 *
 * 暫定光源：天頂方向（0,1,0），Phase 4 升級為 Minecraft 動態光照。
 */
#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : enable

// ─── 主要 payload ───
layout(location = 0) rayPayloadInEXT vec4 hitPayload;

// ─── 陰影 payload（secondary ray） ───
layout(location = 1) rayPayloadEXT bool isShadowed;

// ─── 頂點屬性 ───
hitAttributeEXT vec2 barycentrics;

// ─── Descriptor Set ───
layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;

layout(set = 0, binding = 2) uniform CameraUBO {
    mat4 viewInverse;
    mat4 projInverse;
    vec4 cameraPos;
    float time;
    float _pad0, _pad1, _pad2;
} camera;

// 頂點 buffer（10 floats per vertex: xyz, nxnynz, rgba）
layout(set = 0, binding = 3, std430) readonly buffer VertexBuffer {
    float verts[];
} vertexBuffer;

// 應力 buffer（float per chunk, indexed by instanceCustomIndex）
layout(set = 0, binding = 4, std430) readonly buffer StressBuffer {
    float stressValues[];
} stressBuffer;

// ─── 工具函式 ───

// 取得頂點屬性（stride = 10 floats）
vec3 getPosition(uint baseIndex) {
    return vec3(
        vertexBuffer.verts[baseIndex * 10 + 0],
        vertexBuffer.verts[baseIndex * 10 + 1],
        vertexBuffer.verts[baseIndex * 10 + 2]
    );
}

vec3 getNormal(uint baseIndex) {
    return normalize(vec3(
        vertexBuffer.verts[baseIndex * 10 + 3],
        vertexBuffer.verts[baseIndex * 10 + 4],
        vertexBuffer.verts[baseIndex * 10 + 5]
    ));
}

vec3 getColor(uint baseIndex) {
    return vec3(
        vertexBuffer.verts[baseIndex * 10 + 6],
        vertexBuffer.verts[baseIndex * 10 + 7],
        vertexBuffer.verts[baseIndex * 10 + 8]
    );
}

// 應力熱圖：0=冷（藍）→ 0.5=中（綠）→ 1=熱（紅）
vec3 stressHeatmap(float t) {
    t = clamp(t, 0.0, 1.0);
    if (t < 0.5) {
        return mix(vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 0.0), t * 2.0);
    } else {
        return mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), (t - 0.5) * 2.0);
    }
}

// 簡易偽亂數（基於像素座標 + 時間）
float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233)) + camera.time) * 43758.5453);
}

// cosine-weighted hemisphere sample（局部坐標）
vec3 sampleHemisphere(vec3 normal, float r1, float r2) {
    float phi      = 6.283185 * r1;
    float cosTheta = sqrt(r2);
    float sinTheta = sqrt(1.0 - r2);

    vec3 up = abs(normal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent   = normalize(cross(up, normal));
    vec3 bitangent = cross(normal, tangent);

    return normalize(
        sinTheta * cos(phi) * tangent +
        sinTheta * sin(phi) * bitangent +
        cosTheta * normal
    );
}

// ─── Main ───

void main() {
    // 重心坐標插值
    vec3 bary = vec3(1.0 - barycentrics.x - barycentrics.y,
                     barycentrics.x, barycentrics.y);

    // 取得三角形頂點索引（gl_PrimitiveID = triangle index）
    // 每個 quad = 2 個三角形（idx pattern: 0,1,2,0,2,3）
    uint quadIdx  = uint(gl_PrimitiveID) / 2;
    uint triInQuad = uint(gl_PrimitiveID) % 2;
    uint v0, v1, v2;
    if (triInQuad == 0) {
        v0 = quadIdx * 4 + 0;
        v1 = quadIdx * 4 + 1;
        v2 = quadIdx * 4 + 2;
    } else {
        v0 = quadIdx * 4 + 0;
        v1 = quadIdx * 4 + 2;
        v2 = quadIdx * 4 + 3;
    }

    // 插值法向量
    vec3 n0 = getNormal(v0);
    vec3 n1 = getNormal(v1);
    vec3 n2 = getNormal(v2);
    vec3 normal = normalize(bary.x * n0 + bary.y * n1 + bary.z * n2);
    // 確保法向量朝向光線入射方向
    if (dot(normal, gl_WorldRayDirectionEXT) > 0.0) normal = -normal;

    // 插值顏色
    vec3 c0 = getColor(v0);
    vec3 c1 = getColor(v1);
    vec3 c2 = getColor(v2);
    vec3 albedo = bary.x * c0 + bary.y * c1 + bary.z * c2;

    // 世界空間碰撞點
    vec3 hitPos = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;

    // ─── 直接光照（Lambert + 點光源 @ 天頂上方 1000m） ───
    vec3 sunDir = normalize(vec3(0.3, 1.0, 0.2));  // 暫定太陽方向
    float NdotL = max(dot(normal, sunDir), 0.0);

    // ─── 陰影光線 ───
    float shadow = 1.0;
    if (NdotL > 0.001) {
        isShadowed = true;
        traceRayEXT(
            topLevelAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
            0xFF,
            0,     // sbtOffset（hit group 0, shadow不需要chit）
            0,
            1,     // missIndex = 1（shadow miss shader）
            hitPos + normal * 0.001,
            0.001,
            sunDir,
            1000.0,
            1      // payload location 1 = isShadowed
        );
        shadow = isShadowed ? 0.2 : 1.0;  // 在陰影中 → 環境光 only
    }

    // ─── 直接光照計算 ───
    vec3 ambient = albedo * 0.15;
    vec3 diffuse = albedo * NdotL * shadow * 0.85;
    vec3 directLight = ambient + diffuse;

    // ─── 1-bounce GI（hemisphere sample） ───
    vec2 pixCoord = vec2(gl_LaunchIDEXT.xy);
    float r1 = rand(pixCoord);
    float r2 = rand(pixCoord + vec2(17.3, 43.7));
    vec3 giDir = sampleHemisphere(normal, r1, r2);

    vec4 giPayload = vec4(0.0);
    hitPayload = vec4(0.0);  // 暫借 payload 做 GI（location=0）
    traceRayEXT(
        topLevelAS,
        gl_RayFlagsOpaqueEXT,
        0xFF, 0, 0, 0,
        hitPos + normal * 0.001,
        0.001,
        giDir,
        100.0,
        0
    );
    giPayload = hitPayload;
    vec3 gi = giPayload.rgb * max(dot(normal, giDir), 0.0) * 0.3;

    // ─── 應力熱圖疊加 ───
    float stressViz = 0.0;
    uint chunkId = uint(gl_InstanceCustomIndexEXT);
    if (chunkId < 65536) { // bounds check
        float rawStress = stressBuffer.stressValues[chunkId];
        stressViz = clamp(rawStress, 0.0, 1.0);
    }
    vec3 heatColor = stressHeatmap(stressViz);
    float stressBlend = stressViz * 0.5; // 50% 最大疊加強度
    vec3 finalColor = mix(directLight + gi, heatColor, stressBlend);

    hitPayload = vec4(finalColor, 1.0);
}
