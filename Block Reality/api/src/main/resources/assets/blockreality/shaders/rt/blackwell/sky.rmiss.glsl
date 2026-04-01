#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Blackwell Sky Miss Shader
//  反射/GI 射線未命中 → Preetham 大氣模型天空色
//  與 Ada sky.rmiss.glsl 邏輯相同；CameraFrame 對齊 Blackwell primary.rgen
//  （新增 mfgExposureScale 欄位，避免 UBO layout mismatch）
// ═══════════════════════════════════════════════════════════════════════════
#extension GL_EXT_ray_tracing         : require
#extension GL_EXT_scalar_block_layout : require

layout(location = 0) rayPayloadInEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} payload;

// ★ Blackwell CameraFrame：包含 mfgExposureScale（與 primary.rgen.glsl 對齊）
layout(set = 2, binding = 0, scalar) uniform CameraFrame {
    mat4  invViewProj;
    mat4  prevInvViewProj;
    vec3  camPos;    float _p0;
    vec3  sunDir;    float _p1;
    vec3  sunColor;  float _p2;
    vec3  skyColor;  float _p3;
    uint  frameIndex;
    float aoRadius;
    float aoStrength;
    float reflectionRoughnessThreshold;
    float mfgExposureScale;  // Blackwell DLSS 4 MFG（非 MFG 時 = 1.0）
    float _pad[3];
} cam;

// ─── Preetham A-E 係數（黃昏優化版，與 Ada 相同） ────────────────────────
const vec3 A = vec3(-1.1, -1.05, -1.0);
const vec3 B = vec3(-0.2, -0.20, -0.20);
const vec3 C = vec3( 5.0,  4.5,  2.0);
const vec3 D = vec3(-0.3, -0.35, -0.60);
const vec3 E = vec3( 0.5,  0.5,  0.3);

float perezFunc(vec3 ABC_DE, float theta, float gamma) {
    float ct = cos(theta);
    float cg = cos(gamma);
    return (1.0 + ABC_DE[0] * exp(ABC_DE[1] / max(ct, 0.001))) *
           (1.0 + ABC_DE[2] * exp(ABC_DE[3] * gamma) + ABC_DE[4] * cg * cg);
}

vec3 preethamSky(vec3 rayDir) {
    vec3  sunDirN   = normalize(cam.sunDir);
    float sunZenith = acos(clamp(sunDirN.y, -1.0, 1.0));
    float cosGamma  = clamp(dot(rayDir, sunDirN), -1.0, 1.0);
    float gamma     = acos(cosGamma);
    float theta     = acos(clamp(rayDir.y, -1.0, 1.0));

    vec3 skyLum = vec3(
        perezFunc(vec3(A.r, B.r, C.r), theta, gamma),
        perezFunc(vec3(A.g, B.g, C.g), theta, gamma),
        perezFunc(vec3(A.b, B.b, C.b), theta, gamma)
    );
    vec3 zenithLum = vec3(
        perezFunc(vec3(A.r, B.r, C.r), 0.0, sunZenith),
        perezFunc(vec3(A.g, B.g, C.g), 0.0, sunZenith),
        perezFunc(vec3(A.b, B.b, C.b), 0.0, sunZenith)
    );

    skyLum /= max(zenithLum, vec3(0.0001));
    skyLum *= cam.skyColor;
    skyLum  = clamp(skyLum, vec3(0.0), vec3(8.0));

    float sunDisc = pow(max(cosGamma, 0.0), 1024.0) * 8.0;
    skyLum += cam.sunColor * sunDisc;

    float horizBlend = pow(1.0 - max(rayDir.y, 0.0), 6.0);
    skyLum = mix(skyLum, cam.skyColor * vec3(1.0, 0.8, 0.5), horizBlend * 0.4);

    return skyLum;
}

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);
    payload.radiance = (dir.y < -0.01)
        ? vec3(0.03, 0.025, 0.02)
        : preethamSky(dir);
    payload.hitDist  = -1.0;
    payload.matId    = 0u;
    payload.lodLevel = 3u;
}
