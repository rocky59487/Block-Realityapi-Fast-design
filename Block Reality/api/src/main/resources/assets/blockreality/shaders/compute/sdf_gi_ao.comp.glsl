#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int32 : require

// ══════════════════════════════════════════════════════════════════════════════
// sdf_gi_ao.comp.glsl — SDF Ray Marching GI + AO + Soft Shadow
//
// 使用 Sphere Tracing 在 SDF Volume 中計算遠距全域照明、環境遮蔽與柔和陰影。
// 混合渲染策略：近處 HW RT 結果 + 遠處 SDF 結果線性混合。
//
// Bindings:
//   set 0, b0: SDF 3D texture       (sampler3D, trilinear)
//   set 0, b1: GBuffer depth        (sampler2D)
//   set 0, b2: GBuffer normal       (sampler2D, world-space octahedron)
//   set 0, b3: GI output            (image2D, rgba16f, imageStore)
//   set 0, b4: AO output            (image2D, r8, imageStore)
//   set 0, b5: CameraUBO            (uniform buffer)
//
// Push constants:
//   nearEnd   (float) — HW RT 全強度結束距離
//   farStart  (float) — SDF 全強度開始距離
//   originX/Y/Z (float) — SDF Volume 世界座標原點
//   frameIndex (uint) — 用於 Halton 序列抖動
//
// Specialization constants:
//   SC_0 = GPU_TIER     : 0=Legacy, 1=Ada SM8.9, 2=Blackwell SM10+
//   SC_2 = GI_CONE_COUNT: Ada=4, Blackwell=8
//   SC_3 = AO_RAY_COUNT : Ada=2, Blackwell=4
//
// 參考：
//   - "Enhanced Sphere Tracing" (Keinert et al., 2014)
//   - "Ambient Occlusion via SDF" (Quilez, 2015)
//   - UE5 Lumen: Software Ray Tracing via Distance Fields
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

// ── Specialization Constants ────────────────────────────────────────────────

layout(constant_id = 0) const int GPU_TIER      = 1;   // 0=Legacy, 1=Ada, 2=Blackwell
layout(constant_id = 2) const int GI_CONE_COUNT = 4;   // Ada=4, Blackwell=8
layout(constant_id = 3) const int AO_RAY_COUNT  = 2;   // Ada=2, Blackwell=4

// ── Bindings ────────────────────────────────────────────────────────────────

layout(set = 0, binding = 0) uniform sampler3D u_SDFVolume;  // 3D SDF texture (R16F)
layout(set = 0, binding = 1) uniform sampler2D u_GBufDepth;  // R32F linear depth
layout(set = 0, binding = 2) uniform sampler2D u_GBufNormal; // RG16F octahedron-encoded world normal

layout(set = 0, binding = 3, rgba16f) writeonly uniform image2D u_GIOutput;  // GI radiance output
layout(set = 0, binding = 4, r8)      writeonly uniform image2D u_AOOutput;  // AO output (0=occluded, 1=open)

layout(set = 0, binding = 5) uniform CameraUBO {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec4  cameraPos;       // .xyz = world position
    vec4  sunDir;          // .xyz = normalised sun direction
    vec4  sunColor;        // .rgb = sun radiance
    vec4  skyColor;        // .rgb = ambient sky radiance
    float frameIndex;
    float aoRadius;        // AO sampling radius (blocks)
    float giMaxDist;       // GI max trace distance (blocks)
    float shadowSoftness;  // Soft shadow penumbra factor
} cam;

// ── Push Constants ──────────────────────────────────────────────────────────

layout(push_constant) uniform PushConstants {
    float nearEnd;     // HW RT ↔ SDF blend: HW RT fully on below this
    float farStart;    // HW RT ↔ SDF blend: SDF fully on above this
    float originX;     // SDF Volume world-space origin X
    float originY;
    float originZ;
    uint  frameIdx;    // Frame index for temporal jitter
} pc;

// ── Constants ───────────────────────────────────────────────────────────────

const float SDF_VOLUME_SIZE = 256.0;   // Volume dimension (must match BRSDFVolumeManager.VOLUME_DIM)
const float MAX_MARCH_STEPS = 64.0;    // Maximum sphere trace iterations
const float MIN_HIT_DIST    = 0.01;    // Surface hit threshold (blocks)
const float PI = 3.14159265359;

// ── Helper: Octahedron → World Normal ───────────────────────────────────────

vec3 decodeOctahedronNormal(vec2 oct) {
    vec3 n = vec3(oct.xy, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

// ── Helper: World → SDF UV ──────────────────────────────────────────────────

vec3 worldToSDFUV(vec3 worldPos) {
    return (worldPos - vec3(pc.originX, pc.originY, pc.originZ)) / SDF_VOLUME_SIZE;
}

// ── Helper: Sample SDF ──────────────────────────────────────────────────────

float sampleSDF(vec3 worldPos) {
    vec3 uv = worldToSDFUV(worldPos);
    if (any(lessThan(uv, vec3(0.0))) || any(greaterThan(uv, vec3(1.0))))
        return SDF_VOLUME_SIZE; // Outside volume = far away
    return texture(u_SDFVolume, uv).r;
}

// ── Helper: Reconstruct World Position from Depth ───────────────────────────

vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clipPos = vec4(uv * 2.0 - 1.0, depth, 1.0);
    vec4 worldPos = cam.invViewProj * clipPos;
    return worldPos.xyz / worldPos.w;
}

// ── Sphere Tracing ──────────────────────────────────────────────────────────

// Returns distance to nearest surface along ray, or -1 if no hit
float sphereTrace(vec3 origin, vec3 dir, float maxDist) {
    float t = 0.0;
    for (int i = 0; i < int(MAX_MARCH_STEPS); i++) {
        vec3 p = origin + dir * t;
        float d = sampleSDF(p);
        if (d < MIN_HIT_DIST) return t;
        t += d;
        if (t > maxDist) return -1.0;
    }
    return -1.0;
}

// ── SDF Ambient Occlusion ───────────────────────────────────────────────────

// "Analytical AO" from distance field (Quilez / UE5 Lumen approach)
// Sample SDF at increasing distances along normal; compare to expected distance
float computeAO(vec3 pos, vec3 normal) {
    float ao = 0.0;
    float weight = 1.0;
    float radius = cam.aoRadius;

    for (int i = 0; i < AO_RAY_COUNT; i++) {
        float dist = radius * float(i + 1) / float(AO_RAY_COUNT);
        vec3 samplePos = pos + normal * dist;
        float sdfDist = sampleSDF(samplePos);

        // If SDF < expected distance, surface is occluding
        ao += weight * clamp(sdfDist / dist, 0.0, 1.0);
        weight *= 0.5; // Exponential falloff for farther samples
    }

    return clamp(ao, 0.0, 1.0);
}

// ── Soft Shadow via SDF ─────────────────────────────────────────────────────

float computeSoftShadow(vec3 pos, vec3 lightDir, float maxDist) {
    float result = 1.0;
    float t = 0.5; // Start slightly offset to avoid self-intersection

    for (int i = 0; i < 32; i++) {
        vec3 p = pos + lightDir * t;
        float d = sampleSDF(p);

        if (d < MIN_HIT_DIST) return 0.0; // Full shadow

        // Penumbra estimation (Quilez soft shadow technique)
        result = min(result, cam.shadowSoftness * d / t);
        t += max(d, 0.1); // Advance at least 0.1 to avoid stuck rays

        if (t > maxDist) break;
    }

    return clamp(result, 0.0, 1.0);
}

// ── Halton Sequence (low-discrepancy sampling) ──────────────────────────────

float halton(uint index, uint base) {
    float f = 1.0, result = 0.0;
    uint i = index;
    while (i > 0u) {
        f /= float(base);
        result += f * float(i % base);
        i /= base;
    }
    return result;
}

// ── Cosine-Weighted Hemisphere Direction ────────────────────────────────────

vec3 cosineHemisphere(vec3 normal, float u1, float u2) {
    float phi = 2.0 * PI * u1;
    float cosTheta = sqrt(u2);
    float sinTheta = sqrt(1.0 - u2);

    vec3 tangent = normalize(abs(normal.y) < 0.99 ?
        cross(normal, vec3(0,1,0)) : cross(normal, vec3(1,0,0)));
    vec3 bitangent = cross(normal, tangent);

    return normalize(
        tangent * cos(phi) * sinTheta +
        bitangent * sin(phi) * sinTheta +
        normal * cosTheta
    );
}

// ── Main ────────────────────────────────────────────────────────────────────

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    ivec2 outputSize = imageSize(u_GIOutput);

    if (pixel.x >= outputSize.x || pixel.y >= outputSize.y)
        return;

    vec2 uv = (vec2(pixel) + 0.5) / vec2(outputSize);

    // ── Read GBuffer ────────────────────────────────────────────────────
    float depth = texture(u_GBufDepth, uv).r;

    // Skip sky pixels
    if (depth >= 1.0) {
        imageStore(u_GIOutput, pixel, vec4(cam.skyColor.rgb, 0.0));
        imageStore(u_AOOutput, pixel, vec4(1.0));
        return;
    }

    vec3 worldPos = reconstructWorldPos(uv, depth);
    vec3 normal = decodeOctahedronNormal(texture(u_GBufNormal, uv).rg);

    // ── Distance-based blend factor ─────────────────────────────────────
    float distToCamera = length(worldPos - cam.cameraPos.xyz);
    float sdfBlend = smoothstep(pc.nearEnd, pc.farStart, distToCamera);

    // If fully in HW RT range, output zero contribution (HW RT handles it)
    if (sdfBlend < 0.01) {
        imageStore(u_GIOutput, pixel, vec4(0.0));
        imageStore(u_AOOutput, pixel, vec4(1.0));
        return;
    }

    // ── Ambient Occlusion ───────────────────────────────────────────────
    float ao = computeAO(worldPos + normal * 0.1, normal);

    // ── Soft Shadow ─────────────────────────────────────────────────────
    float shadow = computeSoftShadow(worldPos + normal * 0.1, cam.sunDir.xyz, 64.0);

    // ── Global Illumination (Cone Tracing) ──────────────────────────────
    vec3 gi = vec3(0.0);
    uint seed = pc.frameIdx * uint(outputSize.x) * uint(pixel.y) + uint(pixel.x);

    for (int i = 0; i < GI_CONE_COUNT; i++) {
        // Jittered cosine-weighted hemisphere direction
        float u1 = halton(seed + uint(i) * 7u + 1u, 2u);
        float u2 = halton(seed + uint(i) * 7u + 1u, 3u);
        vec3 dir = cosineHemisphere(normal, u1, u2);

        float hitDist = sphereTrace(worldPos + normal * 0.2, dir, cam.giMaxDist);

        if (hitDist > 0.0) {
            // Simple approximation: assume hit surface reflects sky + sun
            vec3 hitPos = worldPos + normal * 0.2 + dir * hitDist;
            float hitShadow = computeSoftShadow(hitPos, cam.sunDir.xyz, 32.0);
            vec3 bounceLight = cam.skyColor.rgb * 0.3 + cam.sunColor.rgb * hitShadow * 0.7;
            gi += bounceLight / float(GI_CONE_COUNT);
        } else {
            // Ray escaped — contributes sky color
            gi += cam.skyColor.rgb / float(GI_CONE_COUNT);
        }
    }

    // ── Compose output (blended by distance) ────────────────────────────
    vec3 finalGI = gi * shadow * sdfBlend;
    float finalAO = mix(1.0, ao, sdfBlend);

    imageStore(u_GIOutput, pixel, vec4(finalGI, sdfBlend));
    imageStore(u_AOOutput, pixel, vec4(finalAO));
}
