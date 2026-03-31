#version 460
// ═══════════════════════════════════════════════════════════════════════════
//  Block Reality — Sky Miss Shader（反射/GI 射線未命中 → 天空色）
//  Preetham 大氣模型（精確版，比 miss.rmiss.glsl 更精細）
// ═══════════════════════════════════════════════════════════════════════════
#extension GL_EXT_ray_tracing      : require
#extension GL_EXT_scalar_block_layout : require

layout(location = 0) rayPayloadInEXT struct {
    vec3  radiance;
    float hitDist;
    uint  matId;
    uint  lodLevel;
} payload;

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
    float _pad[4];
} cam;

// ─── Preetham A-E 係數（黃昏優化版） ─────────────────────────────────────
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
    vec3  sunDirN = normalize(cam.sunDir);
    float sunZenith = acos(clamp(sunDirN.y, -1.0, 1.0));

    float cosGamma = clamp(dot(rayDir, sunDirN), -1.0, 1.0);
    float gamma    = acos(cosGamma);
    float theta    = acos(clamp(rayDir.y, -1.0, 1.0));

    // Preetham sky luminance
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

    // 太陽 disc（cos angle falloff）
    float sunDisc = pow(max(cosGamma, 0.0), 1024.0) * 8.0;
    skyLum += cam.sunColor * sunDisc;

    // 地平線霧（增加真實感）
    float horizBlend = pow(1.0 - max(rayDir.y, 0.0), 6.0);
    skyLum = mix(skyLum, cam.skyColor * vec3(1.0, 0.8, 0.5), horizBlend * 0.4);

    return skyLum;
}

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);

    payload.radiance = (dir.y < -0.01)
        ? vec3(0.03, 0.025, 0.02)   // 地面下方（罕見）
        : preethamSky(dir);

    payload.hitDist  = -1.0;
    payload.matId    = 0u;
    payload.lodLevel = 3u;
}
