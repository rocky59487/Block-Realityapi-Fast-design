<<<<<<< HEAD
package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.EnumSet;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.*;

/**
 * Main Vulkan ray tracing pipeline for Block Reality.
 * Dispatches ray tracing for shadows, reflections, ambient occlusion, and global illumination.
 *
 * <p>Uses VK_KHR_ray_tracing_pipeline to trace rays against the BLAS/TLAS built by
 * {@code BRVulkanBVH}. The output is written to an image that is interoped to GL via
 * {@code BRVulkanInterop} and then denoised by {@link BRSVGFDenoiser}.</p>
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
@OnlyIn(Dist.CLIENT)
public final class BRVulkanRT {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VulkanRT");

    // ── RT Effects ──────────────────────────────────────────────────────────

    public enum RTEffect {
        SHADOWS(true, 0.5f),               // ~0.5ms @ 1080p
        REFLECTIONS(false, 1.0f),          // ~1.0ms
        AMBIENT_OCCLUSION(false, 0.3f),
        GLOBAL_ILLUMINATION(false, 3.0f);

        public boolean enabledByDefault;
        public float estimatedCostMs;

        RTEffect(boolean d, float c) {
            enabledByDefault = d;
            estimatedCostMs = c;
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    /** Shader Binding Table handle size (typical, queried from device properties at init). */
    private static final int SBT_HANDLE_SIZE = 32;

    /** SBT handle alignment requirement. */
    private static final int SBT_HANDLE_ALIGNMENT = 64;

    /** Max recursion depth — 2 supports primary shadow + 1 GI bounce (Blackwell). */
    private static final int MAX_RT_RECURSION_DEPTH = 2;

    // ── GLSL Shader Sources ─────────────────────────────────────────────────
    //
    // These shaders are compiled at runtime via BRVulkanDevice.compileGLSLtoSPIRV().
    // In production builds they should be pre-compiled to SPIR-V resources.
    //
    // Specialization constants (applied in BRAdaRTConfig.buildSpecializationInfo):
    //   SC_0 = GPU_TIER  : 0=Legacy, 1=Ada SM8.9, 2=Blackwell SM10+
    //   SC_1 = AO_SAMPLES: 0=Ada(8),  1=Blackwell(16)   [raygen/rtao]
    //          MAX_BOUNCES: 0=Ada(1),  1=Blackwell(2)    [closesthit]

    // ── Raygen — shadow + reflection + SER + far-field GI via SVDAG ────────
    private static final String RAYGEN_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing            : require
            #extension GL_NV_ray_tracing_invocation_reorder : enable
            #extension GL_EXT_ray_query              : enable

            // ── Descriptor bindings ──────────────────────────────────────────
            layout(set=0, binding=0) uniform accelerationStructureEXT u_TLAS;
            layout(set=0, binding=1, rgba16f) uniform image2D u_RTOutput;     // shadow.r + refl.gba
            layout(set=0, binding=2) uniform sampler2D u_GBufDepth;
            layout(set=0, binding=3) uniform sampler2D u_GBufNormal;       // world-space octahedron
            layout(set=0, binding=4) uniform sampler2D u_GBufMaterial;     // roughness.r metallic.g
            layout(set=0, binding=5) uniform sampler2D u_GBufMotion;       // motion vector (for TAA)

            // CameraUBO 256-byte layout (std140, matches BRVulkanDevice.createCameraUBO):
            //   offset   0: mat4  invViewProj
            //   offset  64: mat4  prevInvViewProj  (SVGF temporal reprojection)
            //   offset 128: vec4  weatherData      (.x=wetness .y=snowCoverage)
            //   offset 144: float frameIndex       (Halton seed)
            //   offset 148: float _pad[3]          (std140 padding → next vec4 at 160)
            //   offset 160: vec4  cameraPos        (.xyz = world pos)
            //   offset 176: vec4  sunDir           (.xyz = normalised sun direction)
            layout(set=0, binding=6) uniform CameraUBO {
                mat4  invViewProj;
                mat4  prevInvViewProj;
                vec4  weatherData;
                float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4  cameraPos;
                vec4  sunDir;
            } cam;

            // SVDAG buffer for far-field LOD 3 GI (Ada+ only) — 128+ chunk range
            layout(set=0, binding=7, std430) readonly buffer SVDAGBuffer {
                uint  nodes[];
            } u_SVDAG;

            // ── Specialization constants ─────────────────────────────────────
            layout(constant_id = 0) const int  SC_GPU_TIER   = 0; // 0=legacy,1=ada,2=blackwell
            layout(constant_id = 1) const int  SC_AO_SAMPLES = 8; // 8 or 16

            // ── Payload types ────────────────────────────────────────────────
            layout(location = 0) rayPayloadEXT vec4 shadowPayload;   // .r=lit(1)/shadow(0)
            layout(location = 1) rayPayloadEXT vec4 reflPayload;     // .rgb=radiance .a=hitDist

            // ── Hit object (SER) — Ada+ ──────────────────────────────────────
            #ifdef GL_NV_ray_tracing_invocation_reorder
            hitObjectNV hitObj;
            #endif

            // ── Utility: octahedron-decode normal ───────────────────────────
            vec3 octDecode(vec2 f) {
                vec3 n = vec3(f.x, f.y, 1.0 - abs(f.x) - abs(f.y));
                if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
                return normalize(n);
            }

            // ── Utility: GGX NDF importance sampling ────────────────────────
            vec3 importanceSampleGGX(vec2 Xi, vec3 N, float roughness) {
                float a = roughness * roughness;
                float phi = 6.283185 * Xi.x;
                float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (a*a - 1.0) * Xi.y));
                float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
                vec3 H = vec3(cos(phi)*sinTheta, sin(phi)*sinTheta, cosTheta);
                vec3 up = abs(N.z) < 0.999 ? vec3(0,0,1) : vec3(1,0,0);
                vec3 T = normalize(cross(up, N));
                vec3 B = cross(N, T);
                return normalize(T*H.x + B*H.y + N*H.z);
            }

            // ── Utility: low-discrepancy noise (Halton) ──────────────────────
            float halton(uint index, uint base) {
                float f = 1.0, r = 0.0;
                uint idx = index;
                for (uint i = 0; i < 16; i++) {
                    if (idx == 0u) break;
                    f /= float(base);
                    r += f * float(idx % base);
                    idx /= base;
                }
                return r;
            }

            // ── Utility: SVDAG march for far-field GI (LOD 3, 128+ chunks) ──
            // Simplified 8-bit voxel lookup; full SVO DDA omitted for clarity.
            vec3 svdagAmbient(vec3 dir) {
                // Placeholder: return a sky gradient for LOD3 indirect light.
                // Full SVO DDA traversal would read u_SVDAG.nodes[] here.
                float sky = max(0.0, dot(dir, cam.sunDir.xyz));
                return mix(vec3(0.3, 0.5, 0.8), vec3(1.0, 0.95, 0.8), sky);
            }

            void main() {
                ivec2 pixel   = ivec2(gl_LaunchIDEXT.xy);
                ivec2 imgSize = ivec2(gl_LaunchSizeEXT.xy);
                vec2  uv      = (vec2(pixel) + 0.5) / vec2(imgSize);

                // ── Sample GBuffer ────────────────────────────────────────────
                float depth    = texture(u_GBufDepth, uv).r;
                if (depth >= 0.9999) {
                    // Sky pixel — no shadow, sky reflection
                    imageStore(u_RTOutput, pixel, vec4(1.0, 0.5, 0.6, 0.7)); // lit + sky
                    return;
                }

                vec4  matData  = texture(u_GBufMaterial, uv);
                float roughness = matData.r;
                float metallic  = matData.g;
                vec4  normalRaw = texture(u_GBufNormal, uv);
                vec3  N         = octDecode(normalRaw.xy);

                // ── Reconstruct world position ───────────────────────────────
                vec4 ndcPos   = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 worldH   = cam.invViewProj * ndcPos;
                vec3 worldPos = worldH.xyz / worldH.w;

                // ── Weather BRDF modification ────────────────────────────────
                float wetness     = cam.weatherData.x;
                float snowCoverage= cam.weatherData.y;
                // Wet surfaces: roughness↓, metallic stays
                float effRoughness = mix(roughness, roughness * 0.35, wetness);
                // Snow: roughness=1.0, metallic=0
                effRoughness = mix(effRoughness, 1.0, snowCoverage * step(0.7, N.y));

                // ── Shadow Ray ───────────────────────────────────────────────
                vec3 shadowOrigin = worldPos + N * 0.005;
                shadowPayload = vec4(1.0); // assume lit

                #ifdef GL_NV_ray_tracing_invocation_reorder
                if (SC_GPU_TIER >= 1) {
                    // Ada/Blackwell SER path: record hit object before reorder
                    hitObjectTraceRayNV(hitObj, u_TLAS,
                        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                        0xFF, 0, 0, 0,
                        shadowOrigin, 0.001, cam.sunDir.xyz, 1000.0, 0);
                    // Reorder threads by material/LOD hint (0 = shadow group)
                    reorderThreadNV(hitObj, 0u, 1u);
                    // Execute the hit or miss shader
                    hitObjectExecuteShaderNV(hitObj, 0);
                } else
                #endif
                {
                    traceRayEXT(u_TLAS,
                        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                        0xFF, 0, 0, 0,
                        shadowOrigin, 0.001, cam.sunDir.xyz, 1000.0, 0);
                }
                float shadowFactor = shadowPayload.r;

                // ── Reflection Ray (GGX importance sampling) ─────────────────
                vec3 reflRadiance = vec3(0.0);
                if (roughness < 0.85 && metallic > 0.03) {
                    uint frameIdx = uint(cam.frameIndex);
                    vec2  Xi  = vec2(halton(frameIdx, 2u), halton(frameIdx, 3u));
                    vec3  V   = normalize(cam.cameraPos.xyz - worldPos);
                    vec3  H   = importanceSampleGGX(Xi, N, effRoughness);
                    vec3  rDir = reflect(-V, H);

                    reflPayload = vec4(0.0);

                    #ifdef GL_NV_ray_tracing_invocation_reorder
                    if (SC_GPU_TIER >= 1) {
                        hitObjectTraceRayNV(hitObj, u_TLAS,
                            gl_RayFlagsOpaqueEXT,
                            0xFF, 1, 0, 1,
                            worldPos + N * 0.01, 0.01, rDir, 500.0, 1);
                        // Reorder by material hit (1 = reflection group)
                        reorderThreadNV(hitObj, 1u, 1u);
                        hitObjectExecuteShaderNV(hitObj, 1);
                    } else
                    #endif
                    {
                        traceRayEXT(u_TLAS, gl_RayFlagsOpaqueEXT,
                            0xFF, 1, 0, 1,
                            worldPos + N * 0.01, 0.01, rDir, 500.0, 1);
                    }

                    float hitDist = reflPayload.a;
                    if (hitDist <= 0.0) {
                        // Miss — sample sky or SVDAG far-field GI
                        if (SC_GPU_TIER >= 1) {
                            reflRadiance = svdagAmbient(rDir); // Ada: SVDAG GI
                        } else {
                            float skyL = max(0.0, dot(rDir, cam.sunDir.xyz));
                            reflRadiance = mix(vec3(0.3,0.5,0.8), vec3(1.0,0.95,0.8), skyL);
                        }
                    } else {
                        reflRadiance = reflPayload.rgb;
                    }

                    // Fresnel–Schlick approximation (metallic blend)
                    float NdotV  = max(dot(N, V), 0.0);
                    float fresnel = metallic + (1.0 - metallic) * pow(1.0 - NdotV, 5.0);
                    reflRadiance *= fresnel;
                }

                // ── Output packing ───────────────────────────────────────────
                // R = shadow factor (1=lit, 0=shadow)
                // GB = reflection radiance .rg, A = 1.0（固定不透明）
                // ★ P7-fix: A 強制 1.0，reflRadiance.b 捨棄，與外部 shader 格式一致。
                imageStore(u_RTOutput, pixel,
                    vec4(shadowFactor, reflRadiance.rg, 1.0));
            }
            """;

    // ── Closest hit — PBR shading + physics stress heatmap + GI bounce ──────
    private static final String CLOSEST_HIT_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            // Payload: .rgb=radiance .a=hitDist (negative = shadow hit)
            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;
            layout(location = 1) rayPayloadInEXT vec4 reflPayload;

            // ── Hit attributes ────────────────────────────────────────────────
            hitAttributeEXT vec2 hitBaryCoords;

            // ── TLAS for recursive tracing ────────────────────────────────────
            layout(set=0, binding=0) uniform accelerationStructureEXT u_TLAS;

            // ── Material SSBO (matId → phys props) ───────────────────────────
            struct MatEntry { vec4 albedo; float roughness; float metallic; float emissive; float stress; };
            layout(set=0, binding=8, std430) readonly buffer MaterialBuffer {
                MatEntry materials[];
            } u_Materials;

            // ── Geometry Buffers ─────────────────────────────────────────────
            layout(set=0, binding=9, std430) readonly buffer VertexBuffer { float v[]; } u_Vertices;
            layout(set=0, binding=10, std430) readonly buffer IndexBuffer { uint i[]; } u_Indices;

            // ── Emissive Light Tree (ReSTIR DI) ─────────────────────────────
            struct LightEntry { vec4 posInt; vec4 colRad; }; // pos.xyz, intensity.w | color.rgb, radius.w
            layout(set=0, binding=11, std430) readonly buffer LightBuffer {
                uint lightCount;
                LightEntry lights[];
            } u_Lights;

            layout(set=0, binding=6) uniform CameraUBO {
                mat4  invViewProj;
                mat4  prevInvViewProj;
                vec4  weatherData;
                float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4  cameraPos;
                vec4  sunDir;
            } cam;

            layout(constant_id = 0) const int SC_GPU_TIER   = 0;
            layout(constant_id = 1) const int SC_MAX_BOUNCES = 1; // 1=Ada, 2=Blackwell

            // ── Utility: low-discrepancy noise (Halton) ──────────────────────
            float halton(uint index, uint base) {
                float f = 1.0, r = 0.0;
                uint idx = index;
                for (uint i = 0; i < 16; i++) {
                    if (idx == 0u) break;
                    f /= float(base);
                    r += f * float(idx % base);
                    idx /= base;
                }
                return r;
            }

            // ── GGX BRDF ─────────────────────────────────────────────────────
            float D_GGX(float NdotH, float a) {
                float a2 = a * a;
                float d  = (NdotH * a2 - NdotH) * NdotH + 1.0;
                return a2 / (3.14159265 * d * d);
            }
            float G_Smith(float NdotV, float NdotL, float a) {
                float k = a * 0.5;
                float gV = NdotV / (NdotV * (1.0 - k) + k);
                float gL = NdotL / (NdotL * (1.0 - k) + k);
                return gV * gL;
            }
            vec3 F_Schlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
            }

            void main() {
                // Shadow hit — return occluded
                shadowPayload = vec4(0.0, 0.0, 0.0, 1.0);

                // If we also have reflection payload — compute basic PBR shading
                uint matId = uint(gl_InstanceCustomIndexEXT) & 0xFFu;
                if (matId < 256u) {
                    MatEntry mat = u_Materials.materials[matId];

                    // Per-vertex normal from SSBO (assuming 8-float layout: pos3, normal3, uv2)
                    uint pId = uint(gl_PrimitiveID);
                    uint i0 = u_Indices.i[3 * pId + 0];
                    uint i1 = u_Indices.i[3 * pId + 1];
                    uint i2 = u_Indices.i[3 * pId + 2];

                    vec3 n0 = vec3(u_Vertices.v[8 * i0 + 3], u_Vertices.v[8 * i0 + 4], u_Vertices.v[8 * i0 + 5]);
                    vec3 n1 = vec3(u_Vertices.v[8 * i1 + 3], u_Vertices.v[8 * i1 + 4], u_Vertices.v[8 * i1 + 5]);
                    vec3 n2 = vec3(u_Vertices.v[8 * i2 + 3], u_Vertices.v[8 * i2 + 4], u_Vertices.v[8 * i2 + 5]);

                    float baryW = 1.0 - hitBaryCoords.x - hitBaryCoords.y;
                    vec3 N = normalize(n0 * baryW + n1 * hitBaryCoords.x + n2 * hitBaryCoords.y);

                    vec3 V    = -gl_WorldRayDirectionEXT;
                    vec3 L    = cam.sunDir.xyz;
                    vec3 H    = normalize(V + L);

                    float NdotL = max(dot(N, L), 0.0);
                    float NdotV = max(dot(N, V), 0.0);
                    float NdotH = max(dot(N, H), 0.0);
                    float VdotH = max(dot(V, H), 0.0);

                    // Weather: wet surface modification
                    float ro = mix(mat.roughness, mat.roughness * 0.35, cam.weatherData.x);
                    ro = mix(ro, 1.0, cam.weatherData.y * step(0.7, N.y));

                    float a   = max(ro * ro, 0.01);
                    vec3 F0   = mix(vec3(0.04), mat.albedo.rgb, mat.metallic);
                    vec3 F    = F_Schlick(VdotH, F0);
                    float D   = D_GGX(NdotH, a);
                    float G   = G_Smith(NdotV, NdotL, a);
                    vec3 spec = (D * G * F) / max(4.0 * NdotV * NdotL, 0.001);

                    vec3 kd  = (1.0 - F) * (1.0 - mat.metallic);
                    vec3 directDiffuse = (kd * mat.albedo.rgb / 3.14159265);
                    vec3 col = (directDiffuse + spec) * NdotL;
                    vec3 worldPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;

                    // ── ReSTIR DI: Sample one dynamic light explicitly ──
                    vec3 dynamicLight = vec3(0.0);
                    if (u_Lights.lightCount > 0u) {
                        uint fId = uint(cam.frameIndex);
                        uint pId = uint(gl_PrimitiveID);
                        float rnd = halton(fId * 5u + pId % 3u, 2u);
                        uint lightIdx = min(uint(rnd * float(u_Lights.lightCount)), u_Lights.lightCount - 1u);
                        
                        LightEntry le = u_Lights.lights[lightIdx];
                        vec3 lPos = le.posInt.xyz;
                        vec3 dirToLight = lPos - worldPos;
                        float distToLight = length(dirToLight);
                        dirToLight /= distToLight;
                        
                        float lNdotL = max(dot(N, dirToLight), 0.0);
                        if (lNdotL > 0.0) {
                            // Uniform PDF
                            float pdf = 1.0 / float(u_Lights.lightCount);
                            float atten = le.posInt.w / max(distToLight * distToLight, 0.01);
                            
                            // Trace shadow ray
                            // We use a local shadow trace by hijacking reflPayload (with careful restore) or using a dedicated shadow trace!
                            // Standard way: call traceRayEXT with shadowPayload (location = 0)
                            shadowPayload.r = 1.0; 
                            traceRayEXT(u_TLAS, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT | gl_RayFlagsSkipClosestHitShaderEXT, 
                                        0xFF, 0, 0, 0, worldPos + N * 0.01, 0.01, dirToLight, distToLight - 0.05, 0);
                            
                            if (shadowPayload.r > 0.5) { 
                                // Unoccluded
                                vec3 l_col = le.colRad.rgb;
                                dynamicLight = (directDiffuse + spec) * l_col * atten * lNdotL / pdf;
                            }
                        }
                    }
                    col += dynamicLight;

                    float currentBounce = reflPayload.w;
                    vec3 indirectLight = vec3(0.0);

                    if (SC_MAX_BOUNCES > 0 && currentBounce < float(SC_MAX_BOUNCES)) {
                        // Cosine-weighted hemisphere sampling for Lambertian GI
                        uint pId = uint(gl_PrimitiveID);
                        uint fId = uint(cam.frameIndex);
                        vec2 Xi = vec2(halton(fId * 4u + uint(currentBounce)*2u, 2u), 
                                       halton(fId * 4u + uint(currentBounce)*2u + 1u, 3u));
                        
                        float phi = 6.283185307 * Xi.x;
                        float cosTheta = sqrt(1.0 - Xi.y);
                        float sinTheta = sqrt(Xi.y);
                        
                        vec3 H_hemi = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
                        vec3 up = abs(N.z) < 0.999 ? vec3(0,0,1) : vec3(1,0,0);
                        vec3 T_hemi = normalize(cross(up, N));
                        vec3 B_hemi = cross(N, T_hemi);
                        vec3 diffDir = normalize(T_hemi * H_hemi.x + B_hemi * H_hemi.y + N * H_hemi.z);
                        
                        // Trace recursive bounce
                        reflPayload.w = currentBounce + 1.0;
                        traceRayEXT(u_TLAS, gl_RayFlagsOpaqueEXT, 0xFF, 1, 0, 1, 
                                    gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT + N * 0.01, 
                                    0.01, diffDir, 500.0, 1);
                        
                        // Accumulate using Lambertian BRDF (pi is canceled out by PDF = cosTheta/pi)
                        indirectLight = reflPayload.rgb * mat.albedo.rgb;
                    }

                    col += indirectLight;

                    // Physics stress heatmap (red tint for high stress)
                    col = mix(col, vec3(1.0, 0.1, 0.1), clamp(mat.stress * 0.5, 0.0, 0.5));
                    // Emissive
                    col += mat.albedo.rgb * mat.emissive;

                    reflPayload = vec4(col, gl_HitTEXT);
                } else {
                    reflPayload = vec4(0.5, 0.5, 0.5, gl_HitTEXT);
                }
            }
            """;

    // ── Miss — sky radiance (Rayleigh+Mie approximation) ────────────────────
    private static final String MISS_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;
            layout(location = 1) rayPayloadInEXT vec4 reflPayload;

            layout(set=0, binding=6) uniform CameraUBO {
                mat4 invViewProj; mat4 prevInvViewProj;
                vec4 weatherData; float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4 cameraPos; vec4 sunDir;
            } cam;

            // Rayleigh + Mie sky approximation (matches BRAtmosphereEngine output)
            vec3 atmosphere(vec3 dir) {
                float sun = max(0.0, dot(dir, cam.sunDir.xyz));
                float horizon = clamp(1.0 - dir.y, 0.0, 1.0);

                // Rayleigh scattering (blue sky, red at horizon)
                vec3 rayleigh = mix(vec3(0.3, 0.55, 1.0), vec3(1.0, 0.6, 0.3), horizon * 0.7);

                // Sun disc
                float sunDisc = pow(sun, 256.0);
                vec3 sunColor = vec3(1.0, 0.95, 0.85) * 5.0;

                // Mie scattering (glare around sun)
                float mie = pow(sun, 8.0) * 0.3;
                vec3 mieColor = vec3(1.0, 0.9, 0.8) * mie;

                return rayleigh + sunColor * sunDisc + mieColor;
            }

            void main() {
                // Shadow miss = fully lit
                shadowPayload = vec4(1.0, 1.0, 1.0, 0.0);

                // Reflection miss = sky radiance
                vec3 sky = atmosphere(gl_WorldRayDirectionEXT);
                reflPayload = vec4(sky, -1.0); // hitDist = -1 → miss marker
            }
            """;

    // ── Any-hit shader (透明 alpha-test，基礎管線 fallback) ─────────────────
    //
    // 在 Ada 路徑由 ada/transparent.rahit.glsl 取代（有折射/SSS 計算）。
    // 基礎管線僅做機率性丟棄：PCG 雜湊決定是否讓光線穿過半透明方塊。
    private static final String ANYHIT_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            // location 0 = shadow payload, location 1 = reflection payload
            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;

            void main() {
                // PCG hash of pixel coord + primitive ID → stable per-sample noise
                uint seed = uint(gl_LaunchIDEXT.x) * 1973u
                          + uint(gl_LaunchIDEXT.y) * 9277u
                          + uint(gl_PrimitiveID)   * 26699u;
                seed = seed * 747796405u + 2891336453u;
                float rnd = float((seed >> 16u) & 0xFFFFu) / 65535.0;

                // 50% transmission — Phase 3: bind MaterialSSBO for per-matId alpha
                const float ALPHA = 0.5;
                if (rnd > ALPHA) {
                    // Let shadow ray continue through transparent geometry
                    ignoreIntersectionEXT();
                }
                // else: accept intersection → block shadow (opaque to light)
            }
            """;

    // ── Pipeline state ──────────────────────────────────────────────────────

    private static boolean initialized = false;
    private static long rtPipeline;             // VkPipeline (ray tracing)
    private static long rtPipelineLayout;       // VkPipelineLayout
    private static long rtDescriptorSetLayout;
    private static long rtDescriptorPool;
    private static long rtDescriptorSet;
    private static long sbtBuffer;              // Shader Binding Table
    private static long sbtBufferMemory;
    private static final EnumSet<RTEffect> enabledEffects = EnumSet.of(RTEffect.SHADOWS);

    // ── Phase 6: RT 輸出 VkImage 資源（GL/VK 共享用）──────────────────────────
    /** RT 輸出 VkImage（RGBA16F，GENERAL layout，STORAGE + TRANSFER_SRC 用途）。 */
    private static long rtOutputImage       = 0L;
    /** RT 輸出 VkImage 的 VkDeviceMemory（DEVICE_LOCAL，帶 exportable fd 標誌）。 */
    private static long rtOutputImageMemory = 0L;
    /** RT 輸出 VkImageView（VK_IMAGE_VIEW_TYPE_2D，RGBA16F）。 */
    private static long rtOutputImageView   = 0L;
    /** RT 完成 VkSemaphore（exportable fd，供 GL_EXT_semaphore_fd 使用）。 */
    private static long doneSemaphore       = 0L;

    // ── Phase 6: CPU Readback 資源（Fallback 路徑）──────────────────────────────
    /** Host-visible VkBuffer（TRANSFER_DST），用於 vkCmdCopyImageToBuffer。 */
    private static long stagingBuffer       = 0L;
    /** Staging buffer 的 VkDeviceMemory（HOST_VISIBLE + HOST_COHERENT）。 */
    private static long stagingBufferMemory = 0L;
    /** 上一幀 readback 的像素數據（RGBA16F，host 端 ByteBuffer）。 */
    private static ByteBuffer readbackBuffer = null;

    /** 輸出 image 的尺寸（pixels）。 */
    private static int rtOutputWidth  = 0;
    private static int rtOutputHeight = 0;

    // SBT regions
    private static long raygenRegionOffset, raygenRegionStride, raygenRegionSize;
    private static long missRegionOffset, missRegionStride, missRegionSize;
    private static long hitRegionOffset, hitRegionStride, hitRegionSize;

    // Stats
    private static float lastTraceTimeMs;
    private static long totalRaysTraced;
    private static long frameCount;

    /** Lazy-initialized VkRTAO compute pipeline for the Ada Shadow+AO pass. */
    private static com.blockreality.api.client.rendering.vulkan.VkRTAO shadowAoPipeline = null;
    /** NRD SDK denoiser handle; 0 = not yet created or NRD unavailable. */
    private static long nrdDenoiserHandle = 0L;

    private BRVulkanRT() { }

    /**
     * RT 輸出 VkImageView 公開 getter（RT-5-2 GBuffer 接線）。
     *
     * <p>供 {@code BRGBufferAttachments.dispatchReLAXFallback} 以及
     * volumetric / FSR dispatch 取得 RT 輸出圖像 VkImageView。
     *
     * @return rtOutputImageView handle，或 0L（未初始化）
     */
    public static long getRtOutputImageView() {
        return rtOutputImageView;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Initialise the Vulkan ray tracing pipeline.
     *
     * <ol>
     *   <li>Check {@code BRVulkanDevice.isRTSupported()}</li>
     *   <li>Create descriptor set layout (TLAS + output image + GBuffer samplers + UBO)</li>
     *   <li>Create pipeline layout</li>
     *   <li>Load / compile shader modules (raygen, miss, closest-hit)</li>
     *   <li>Create ray tracing pipeline via {@code vkCreateRayTracingPipelinesKHR}</li>
     *   <li>Query SBT handle size and create SBT buffer</li>
     *   <li>Copy shader group handles to SBT</li>
     * </ol>
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("BRVulkanRT.init() called but already initialised");
            return;
        }

        try {
            // Step 1 — device capability check
            if (!BRVulkanDevice.isRTSupported()) {
                LOGGER.warn("Vulkan ray tracing not supported on this device — RT effects disabled");
                return;
            }

            long device = BRVulkanDevice.getVkDevice();
            LOGGER.info("Initialising Vulkan RT pipeline...");

            // Step 2 — descriptor set layout
            // Bindings: 0=TLAS, 1=output image, 2=depth sampler, 3=normal sampler, 4=camera UBO
            rtDescriptorSetLayout = createDescriptorSetLayout(device);

            // Step 3 — pipeline layout
            rtPipelineLayout = createPipelineLayout(device, rtDescriptorSetLayout);

            // Step 4 — shader modules
            long raygenModule = createShaderModule(device, RAYGEN_GLSL, "raygen");
            long missModule   = createShaderModule(device, MISS_GLSL,   "miss");
            long chitModule   = createShaderModule(device, CLOSEST_HIT_GLSL, "closest_hit");
            // Any-hit for transparent geometry (glass/water/leaves alpha-test)
            long ahitModule   = createShaderModule(device, ANYHIT_GLSL, "any_hit");

            // Step 5 — ray tracing pipeline
            // 3 shader groups: raygen | miss | hitgroup(chit + ahit)
            // SBT still has 3 entries — anyhit is inside hitgroup, not a separate slot
            rtPipeline = createRTPipelineWithAnyHit(device, rtPipelineLayout,
                    raygenModule, missModule, chitModule, ahitModule);

            // Destroy shader modules — no longer needed after pipeline creation
            BRVulkanDevice.destroyShaderModule(device, raygenModule);
            BRVulkanDevice.destroyShaderModule(device, missModule);
            BRVulkanDevice.destroyShaderModule(device, chitModule);
            BRVulkanDevice.destroyShaderModule(device, ahitModule);

            // Step 6 — SBT (3 groups: raygen / miss / hitgroup)
            // hitgroup internally references both closesthit + anyhit via pipeline creation;
            // no additional SBT entry is needed for anyhit.
            int handleSize = BRVulkanDevice.getRTShaderGroupHandleSize();
            int alignedHandleSize = alignUp(handleSize, SBT_HANDLE_ALIGNMENT);

            raygenRegionOffset = 0;
            raygenRegionStride = alignedHandleSize;
            raygenRegionSize   = alignedHandleSize;

            missRegionOffset = alignedHandleSize;
            missRegionStride = alignedHandleSize;
            missRegionSize   = alignedHandleSize;

            hitRegionOffset = alignedHandleSize * 2L;
            hitRegionStride = alignedHandleSize;
            hitRegionSize   = alignedHandleSize;

            final int SBT_GROUP_COUNT = 3; // raygen + miss + hitgroup
            long sbtSize = (long) alignedHandleSize * SBT_GROUP_COUNT;
            sbtBuffer = BRVulkanDevice.createBuffer(device, sbtSize,
                    0x00000400 /* VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR */
                    | 0x00020000 /* VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT */
                    | 0x00000002 /* VK_BUFFER_USAGE_TRANSFER_DST_BIT */);
            sbtBufferMemory = BRVulkanDevice.allocateAndBindBuffer(device, sbtBuffer,
                    0x00000002 /* VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT */
                    | 0x00000004 /* VK_MEMORY_PROPERTY_HOST_COHERENT_BIT */);

            // Step 7 — copy shader group handles into SBT
            copyShaderGroupHandlesToSBT(device, rtPipeline, sbtBufferMemory,
                    sbtSize, handleSize, alignedHandleSize, SBT_GROUP_COUNT);

            // Descriptor pool + set
            rtDescriptorPool = createDescriptorPool(device);
            rtDescriptorSet = allocateDescriptorSet(device, rtDescriptorPool, rtDescriptorSetLayout);

            // CameraUBO：建立 256-byte HOST_COHERENT buffer，綁定 descriptor set binding 4
            long ubo = BRVulkanDevice.createCameraUBO(device, rtDescriptorSet);
            if (ubo == 0L) {
                LOGGER.error("Failed to create CameraUBO — RT effects disabled");
                cleanup();
                return;
            }

            initialized = true;
            LOGGER.info("Vulkan RT pipeline initialised successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialise Vulkan RT pipeline — RT effects disabled", e);
            cleanup();
        }
    }

    /**
     * Destroy the RT pipeline and all associated Vulkan resources.
     */
    public static void cleanup() {
        if (!initialized && rtPipeline == 0) {
            return;
        }

        LOGGER.info("Cleaning up Vulkan RT pipeline");
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.deviceWaitIdle(device);

            if (rtDescriptorPool != 0) {
                BRVulkanDevice.destroyDescriptorPool(device, rtDescriptorPool);
                rtDescriptorPool = 0;
                rtDescriptorSet = 0;
            }
            if (sbtBuffer != 0) {
                BRVulkanDevice.destroyBuffer(device, sbtBuffer);
                sbtBuffer = 0;
            }
            if (sbtBufferMemory != 0) {
                BRVulkanDevice.freeMemory(device, sbtBufferMemory);
                sbtBufferMemory = 0;
            }
            if (rtPipeline != 0) {
                BRVulkanDevice.destroyPipeline(device, rtPipeline);
                rtPipeline = 0;
            }
            if (rtPipelineLayout != 0) {
                BRVulkanDevice.destroyPipelineLayout(device, rtPipelineLayout);
                rtPipelineLayout = 0;
            }
            if (rtDescriptorSetLayout != 0) {
                BRVulkanDevice.destroyDescriptorSetLayout(device, rtDescriptorSetLayout);
                rtDescriptorSetLayout = 0;
            }
        } catch (Exception e) {
            LOGGER.error("Error during RT pipeline cleanup", e);
        }

        cleanupOutputImage();

        // Cleanup lazily-initialised Phase 8 subsystems
        if (shadowAoPipeline != null) {
            shadowAoPipeline.cleanup();
            shadowAoPipeline = null;
        }
        if (nrdDenoiserHandle != 0L && BRNRDNative.isNrdAvailable()) {
            BRNRDNative.destroyDenoiser(nrdDenoiserHandle);
            nrdDenoiserHandle = 0L;
        }
        BRReSTIRComputeDispatcher.getInstance().cleanup();
        BRDDGIComputeDispatcher.getInstance().cleanup();
        BRDDGIProbeSystem.getInstance().cleanup();

        initialized = false;
        lastTraceTimeMs = 0;
        totalRaysTraced = 0;
        frameCount = 0;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Phase 6: RT 輸出 VkImage 管理（GL/VK Interop 基礎）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 建立 RT 輸出 VkImage（RGBA16F，帶 external memory export fd）。
     *
     * <p>此方法在 {@link #init()} 成功後，由 {@code VkRTPipeline.init(w,h)} 呼叫。
     * 建立的 VkImage 以 {@code VK_IMAGE_USAGE_STORAGE_BIT | TRANSFER_SRC_BIT} 標記，
     * 可供 shader 寫入（{@code u_RTOutput}）並透過 {@code vkCmdCopyImageToBuffer} 讀回。
     *
     * <p>記憶體使用 {@code VkExportMemoryAllocateInfo}，使外部程序（OpenGL GL_EXT_memory_object_fd）
     * 可透過 {@link #exportOutputMemoryFd()} 取得的 fd 直接映射此記憶體，實現零拷貝 VK→GL 共享。
     *
     * @param width  RT 輸出寬度（像素）
     * @param height RT 輸出高度（像素）
     */
    public static void initOutputImage(int width, int height) {
        if (rtOutputImage != 0) {
            LOGGER.warn("[Phase6] initOutputImage called but output image already exists; skipping");
            return;
        }
        if (!initialized) {
            LOGGER.warn("[Phase6] initOutputImage called before BRVulkanRT.init()");
            return;
        }

        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) {
            LOGGER.error("[Phase6] VkDevice not available");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            rtOutputWidth  = width;
            rtOutputHeight = height;
            final long pixelBytes = (long) width * height * 8L; // RGBA16F = 8 bytes

            // ── Step 1: 建立帶 external memory export 的 VkImage ─────────────────
            boolean canExport = BRVulkanDevice.hasExternalMemory();

            // VkExternalMemoryImageCreateInfo（exportable memory，僅在支援時加入）
            long pNextChain = VK_NULL_HANDLE;
            VkExternalMemoryImageCreateInfo externalMemCreateInfo = null;
            if (canExport) {
                externalMemCreateInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
                pNextChain = externalMemCreateInfo.address();
            }

            VkExtent3D extent = VkExtent3D.calloc(stack)
                .width(width).height(height).depth(1);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(pNextChain)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R16G16B16A16_SFLOAT)     // 97
                .extent(extent)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_STORAGE_BIT |          // shader imageStore
                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT)     // vkCmdCopyImageToBuffer
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkCreateImage failed: {}", result);
                return;
            }
            rtOutputImage = pImage.get(0);

            // ── Step 2: 查詢記憶體需求並分配 VkDeviceMemory ──────────────────────
            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, rtOutputImage, memReqs);

            int memTypeIndex = BRVulkanDevice.findMemoryType(
                memReqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memTypeIndex);

            if (canExport) {
                // VkExportMemoryAllocateInfo（讓 fd 可被匯出給 GL）
                VkExportMemoryAllocateInfo exportMem = VkExportMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
                allocInfo.pNext(exportMem.address());
            }

            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(device, allocInfo, null, pMemory);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkAllocateMemory failed: {}", result);
                vkDestroyImage(device, rtOutputImage, null);
                rtOutputImage = 0;
                return;
            }
            rtOutputImageMemory = pMemory.get(0);
            vkBindImageMemory(device, rtOutputImage, rtOutputImageMemory, 0L);

            // ── Step 3: 建立 VkImageView ─────────────────────────────────────────
            VkImageSubresourceRange subRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(rtOutputImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R16G16B16A16_SFLOAT)
                .subresourceRange(subRange);

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkCreateImageView failed: {}", result);
                // 繼續但 imageView = 0（不影響記憶體 fd 匯出）
            } else {
                rtOutputImageView = pView.get(0);
            }

            // ── Step 4: Image layout 轉換 UNDEFINED → GENERAL ────────────────────
            transitionImageLayout(device,
                rtOutputImage,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL,
                0,                                  // srcAccessMask
                VK_ACCESS_SHADER_WRITE_BIT,         // dstAccessMask
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,  // srcStageMask
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR  // dstStageMask
            );

            // ── Step 5: 更新 Descriptor Set（binding=1 = u_RTOutput image）────────
            if (rtOutputImageView != 0 && rtDescriptorSet != 0) {
                VkDescriptorImageInfo.Buffer imageDesc = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(VK_NULL_HANDLE)
                    .imageView(rtOutputImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

                VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack);
                writeSet.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(rtDescriptorSet)
                    .dstBinding(1)         // binding 1 = u_RTOutput (storage image)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imageDesc);
                vkUpdateDescriptorSets(device, writeSet, null);
                LOGGER.debug("[Phase6] u_RTOutput descriptor updated: view={}", rtOutputImageView);
            }

            // ── Step 6: 建立帶 export 標誌的 VkSemaphore（GPU→GL 同步）───────────
            if (canExport) {
                VkExportSemaphoreCreateInfo exportSem = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);

                VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(exportSem.address());

                LongBuffer pSem = stack.mallocLong(1);
                result = vkCreateSemaphore(device, semInfo, null, pSem);
                if (result == VK_SUCCESS) {
                    doneSemaphore = pSem.get(0);
                } else {
                    LOGGER.warn("[Phase6] vkCreateSemaphore (exportable) failed: {} — GPU sync will use glFlush fallback", result);
                }
            }

            // ── Step 7: 建立 Staging Buffer（CPU readback fallback）───────────────
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(pixelBytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuf = stack.mallocLong(1);
            result = vkCreateBuffer(device, bufInfo, null, pBuf);
            if (result == VK_SUCCESS) {
                stagingBuffer = pBuf.get(0);

                VkMemoryRequirements stagingReqs = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, stagingBuffer, stagingReqs);

                int stagingMemType = BRVulkanDevice.findMemoryType(
                    stagingReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                );

                VkMemoryAllocateInfo stagingAlloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(stagingReqs.size())
                    .memoryTypeIndex(stagingMemType);

                LongBuffer pStagingMem = stack.mallocLong(1);
                result = vkAllocateMemory(device, stagingAlloc, null, pStagingMem);
                if (result == VK_SUCCESS) {
                    stagingBufferMemory = pStagingMem.get(0);
                    vkBindBufferMemory(device, stagingBuffer, stagingBufferMemory, 0L);
                } else {
                    LOGGER.warn("[Phase6] staging buffer memory allocation failed: {} — CPU readback unavailable", result);
                    vkDestroyBuffer(device, stagingBuffer, null);
                    stagingBuffer = 0;
                }
            } else {
                LOGGER.warn("[Phase6] staging buffer creation failed: {} — CPU readback unavailable", result);
            }

            LOGGER.info("[Phase6] RT output image ready: {}×{}, image={}, memory={}, view={}, " +
                "semaphore={}, staging={}, exportable={}",
                width, height, rtOutputImage, rtOutputImageMemory,
                rtOutputImageView, doneSemaphore, stagingBuffer, canExport);

            // RT-5-2: GBuffer 附件隨 RT 輸出解析度一同初始化
            BRGBufferAttachments.getInstance().init(width, height);
            LOGGER.debug("[Phase6] GBuffer attachments initialised alongside RT output ({}×{})", width, height);

        } catch (Exception e) {
            LOGGER.error("[Phase6] initOutputImage failed", e);
            cleanupOutputImage();
        }
    }

    /**
     * Image layout 轉換（single-use command buffer）。
     */
    private static void transitionImageLayout(VkDevice device, long image,
            int oldLayout, int newLayout,
            int srcAccessMask, int dstAccessMask,
            int srcStageMask, int dstStageMask) {

        long cmd = BRVulkanDevice.allocateCommandBuffer();
        if (cmd == VK_NULL_HANDLE) {
            LOGGER.warn("[Phase6] transitionImageLayout: cannot allocate command buffer");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer cmdBuf = new VkCommandBuffer(cmd, device);
            vkBeginCommandBuffer(cmdBuf, beginInfo);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(srcAccessMask)
                .dstAccessMask(dstAccessMask);

            vkCmdPipelineBarrier(cmdBuf,
                srcStageMask, dstStageMask,
                0, null, null, barrier);

            vkEndCommandBuffer(cmdBuf);

            // 提交並等待
            VkQueue queue = BRVulkanDevice.getVkQueueObj();
            if (queue != null) {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmdBuf));
                vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
                vkQueueWaitIdle(queue);
            }

            // 釋放 command buffer
            LongBuffer pPool = stack.longs(BRVulkanDevice.getCommandPoolHandle());
            vkFreeCommandBuffers(device, BRVulkanDevice.getCommandPoolHandle(), cmdBuf);

        } catch (Exception e) {
            LOGGER.warn("[Phase6] transitionImageLayout error: {}", e.getMessage());
        }
    }

    /**
     * 清除 RT 輸出 image 所有資源（VkImage、VkDeviceMemory、VkImageView、VkSemaphore、staging buffer）。
     * 由 {@link #cleanup()} 呼叫。
     */
    private static void cleanupOutputImage() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;
        try {
            if (rtOutputImageView != 0) {
                vkDestroyImageView(device, rtOutputImageView, null);
                rtOutputImageView = 0;
            }
            if (doneSemaphore != 0) {
                vkDestroySemaphore(device, doneSemaphore, null);
                doneSemaphore = 0;
            }
            if (rtOutputImage != 0) {
                vkDestroyImage(device, rtOutputImage, null);
                rtOutputImage = 0;
            }
            if (rtOutputImageMemory != 0) {
                vkFreeMemory(device, rtOutputImageMemory, null);
                rtOutputImageMemory = 0;
            }
            if (stagingBuffer != 0) {
                vkDestroyBuffer(device, stagingBuffer, null);
                stagingBuffer = 0;
            }
            if (stagingBufferMemory != 0) {
                vkFreeMemory(device, stagingBufferMemory, null);
                stagingBufferMemory = 0;
            }
            readbackBuffer = null;
            rtOutputWidth  = 0;
            rtOutputHeight = 0;
        } catch (Exception e) {
            LOGGER.warn("[Phase6] cleanupOutputImage error: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Phase 6: GL/VK fd 匯出
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 匯出 RT 輸出 VkImage 的記憶體為 POSIX opaque fd。
     *
     * <p>回傳的 fd 所有權移交呼叫端（GL）；Vulkan 端不得再使用。
     * 僅在 {@link #initOutputImage} 成功且硬體支援 VK_KHR_external_memory_fd 時有效。
     *
     * @return fd ≥ 0，或 -1 表示不可用
     */
    public static int exportOutputMemoryFd() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null || rtOutputImageMemory == 0 || !BRVulkanDevice.hasExternalMemory()) {
            return -1;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryGetFdInfoKHR fdInfo = VkMemoryGetFdInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                .memory(rtOutputImageMemory)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);

            IntBuffer pFd = stack.mallocInt(1);
            int result = vkGetMemoryFdKHR(device, fdInfo, pFd);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[Phase6] vkGetMemoryFdKHR failed: {}", result);
                return -1;
            }
            int fd = pFd.get(0);
            LOGGER.debug("[Phase6] RT output memory fd={} exported", fd);
            return fd;
        } catch (Exception e) {
            LOGGER.warn("[Phase6] exportOutputMemoryFd error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 匯出 RT 完成 VkSemaphore 為 POSIX opaque fd。
     *
     * <p>GL 端使用 {@code GL_EXT_semaphore_fd} 匯入此 fd 做 GPU 同步，
     * 確保 VK RT 寫入完成後 GL 才讀取共享紋理（取代 glFinish）。
     *
     * @return fd ≥ 0，或 -1 表示不可用
     */
    public static int exportDoneSemaphoreFd() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null || doneSemaphore == 0) return -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreGetFdInfoKHR semFdInfo = VkSemaphoreGetFdInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                .semaphore(doneSemaphore)
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);

            IntBuffer pFd = stack.mallocInt(1);
            int result = vkGetSemaphoreFdKHR(device, semFdInfo, pFd);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[Phase6] vkGetSemaphoreFdKHR failed: {}", result);
                return -1;
            }
            return pFd.get(0);
        } catch (Exception e) {
            LOGGER.warn("[Phase6] exportDoneSemaphoreFd error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * CPU Readback：回傳上一幀 RT 輸出的像素數據（RGBA16F）。
     * 由 {@link #traceRays(int, int)} 在每幀更新後可讀。
     *
     * @return RGBA16F 像素數據，或 null 表示尚無數據
     */
    public static ByteBuffer getReadbackBuffer() {
        return readbackBuffer;
    }

    // ── Effect toggles ──────────────────────────────────────────────────────

    public static void enableEffect(RTEffect effect) {
        enabledEffects.add(effect);
        LOGGER.debug("RT effect enabled: {} (est. {:.1f}ms)", effect.name(), effect.estimatedCostMs);
    }

    public static void disableEffect(RTEffect effect) {
        enabledEffects.remove(effect);
        LOGGER.debug("RT effect disabled: {}", effect.name());
    }

    public static boolean isEffectEnabled(RTEffect effect) {
        return enabledEffects.contains(effect);
    }

    public static EnumSet<RTEffect> getEnabledEffects() {
        return EnumSet.copyOf(enabledEffects);
    }

    // ── Trace dispatch ──────────────────────────────────────────────────────

    /**
     * Dispatch ray tracing for the current frame.
     *
     * <ol>
     *   <li>Record command buffer</li>
     *   <li>Bind RT pipeline</li>
     *   <li>Update descriptor set (TLAS from BRVulkanBVH, output from BRVulkanInterop)</li>
     *   <li>{@code vkCmdTraceRaysKHR} with SBT regions</li>
     *   <li>Submit command buffer</li>
     * </ol>
     *
     * @param width  dispatch width in pixels
     * @param height dispatch height in pixels
     */
    public static void traceRays(int width, int height) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            long commandBuffer = BRVulkanDevice.beginSingleTimeCommands(device);

            // Bind ray tracing pipeline
            BRVulkanDevice.cmdBindPipeline(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */, rtPipeline);

            // Bind descriptor sets
            BRVulkanDevice.cmdBindDescriptorSets(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */,
                    rtPipelineLayout, 0, rtDescriptorSet);

            // Get SBT device address
            long sbtAddress = BRVulkanDevice.getBufferDeviceAddress(device, sbtBuffer);

            // Trace rays
            long startTime = System.nanoTime();
            BRVulkanDevice.cmdTraceRaysKHR(commandBuffer,
                    sbtAddress + raygenRegionOffset, raygenRegionStride, raygenRegionSize,
                    sbtAddress + missRegionOffset, missRegionStride, missRegionSize,
                    sbtAddress + hitRegionOffset, hitRegionStride, hitRegionSize,
                    0, 0, 0, // callable (unused)
                    width, height, 1);

            BRVulkanDevice.endSingleTimeCommands(device, commandBuffer);

            // ── Phase 6D: CPU Readback (RT output image → staging buffer → host) ──────
            // Runs only when the staging buffer was successfully created by initOutputImage().
            // BRVKGLSync.uploadFallbackFrame() reads readbackBuffer each frame.
            if (stagingBuffer != 0L && rtOutputImage != 0L
                    && rtOutputWidth > 0 && rtOutputHeight > 0) {
                VkDevice vkDev   = BRVulkanDevice.getVkDeviceObj();
                VkQueue  vkQueue = BRVulkanDevice.getVkQueueObj();
                long     cmdPool = BRVulkanDevice.getCommandPoolHandle();
                if (vkDev != null && vkQueue != null && cmdPool != 0L) {
                    try (MemoryStack s = MemoryStack.stackPush()) {
                        // Allocate one-time copy command buffer
                        var pCb = s.mallocPointer(1);
                        int cbResult = vkAllocateCommandBuffers(vkDev,
                            VkCommandBufferAllocateInfo.calloc(s)
                                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                                .commandPool(cmdPool)
                                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                .commandBufferCount(1),
                            pCb);
                        if (cbResult == VK_SUCCESS) {
                            VkCommandBuffer cb = new VkCommandBuffer(pCb.get(0), vkDev);
                            vkBeginCommandBuffer(cb,
                                VkCommandBufferBeginInfo.calloc(s)
                                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

                            // Barrier: IMAGE_LAYOUT_GENERAL → TRANSFER_SRC_OPTIMAL
                            VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, s)
                                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                .image(rtOutputImage);
                            toSrc.get(0).subresourceRange()
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1)
                                .baseArrayLayer(0).layerCount(1);
                            vkCmdPipelineBarrier(cb,
                                0x00200000 /* VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR */,
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, null, null, toSrc);

                            // Copy rtOutputImage → stagingBuffer
                            VkBufferImageCopy.Buffer copyRgn = VkBufferImageCopy.calloc(1, s)
                                .bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                            copyRgn.get(0).imageSubresource()
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1);
                            copyRgn.get(0).imageOffset().set(0, 0, 0);
                            copyRgn.get(0).imageExtent().set(rtOutputWidth, rtOutputHeight, 1);
                            vkCmdCopyImageToBuffer(cb,
                                rtOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                stagingBuffer, copyRgn);

                            // Barrier: TRANSFER_SRC_OPTIMAL → GENERAL (restore for next frame)
                            toSrc.get(0)
                                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .newLayout(VK_IMAGE_LAYOUT_GENERAL);
                            vkCmdPipelineBarrier(cb,
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0x00200000 /* VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR */,
                                0, null, null, toSrc);

                            vkEndCommandBuffer(cb);

                            // Submit and stall (per-frame readback — acceptable on Fallback path)
                            vkQueueSubmit(vkQueue,
                                VkSubmitInfo.calloc(s)
                                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                                    .pCommandBuffers(s.pointers(cb)),
                                VK_NULL_HANDLE);
                            vkQueueWaitIdle(vkQueue);
                            vkFreeCommandBuffers(vkDev, cmdPool, cb);

                            // Map staging memory and snapshot into readbackBuffer
                            long stagingSize = (long) rtOutputWidth * rtOutputHeight * 8L; // RGBA16F
                            var pData = s.mallocPointer(1);
                            if (vkMapMemory(vkDev, stagingBufferMemory, 0L, stagingSize, 0, pData)
                                    == VK_SUCCESS) {
                                ByteBuffer mapped = MemoryUtil.memByteBuffer(
                                    pData.get(0), (int) stagingSize);
                                if (readbackBuffer == null
                                        || readbackBuffer.capacity() != (int) stagingSize) {
                                    if (readbackBuffer != null) MemoryUtil.memFree(readbackBuffer);
                                    readbackBuffer = MemoryUtil.memAlloc((int) stagingSize);
                                }
                                readbackBuffer.clear().put(mapped).flip();
                                vkUnmapMemory(vkDev, stagingBufferMemory);
                            }
                        } else {
                            LOGGER.warn("[Phase6D] vkAllocateCommandBuffers failed ({}); skipping readback",
                                cbResult);
                        }
                    } catch (Exception rbEx) {
                        LOGGER.warn("[Phase6D] CPU readback failed: {}", rbEx.getMessage());
                    }
                }
            }

            long elapsed = System.nanoTime() - startTime;
            lastTraceTimeMs = elapsed / 1_000_000.0f;
            totalRaysTraced += (long) width * height;
            frameCount++;
        } catch (Exception e) {
            LOGGER.error("Error during ray trace dispatch", e);
        }
    }

    /**
     * Update the descriptor set bindings for TLAS and output image.
     *
     * @param tlas            Vulkan acceleration structure handle
     * @param outputImageView VkImageView for the RT output
     */
    public static void updateDescriptors(long tlas, long outputImageView) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateRTDescriptorSet(device, rtDescriptorSet, tlas, outputImageView);
        } catch (Exception e) {
            LOGGER.error("Failed to update RT descriptors", e);
        }
    }

    /**
     * Upload camera data to the UBO bound at binding 4.
     */
    public static void setCameraData(Matrix4f invViewProj,
                                     float camX, float camY, float camZ,
                                     float sunDirX, float sunDirY, float sunDirZ) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateCameraUBO(device, rtDescriptorSet,
                    invViewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ);
        } catch (Exception e) {
            LOGGER.error("Failed to update camera UBO", e);
        }
    }

    // ── Temporal reprojection ────────────────────────────────────────────────

    /**
     * 上傳前一幀的 invViewProj 到 CameraUBO offset 64（第二個 mat4）。
     *
     * <p>必須在 {@link #setCameraData} <b>之前</b>呼叫，確保 SVGF denoiser 的
     * temporal reprojection 使用正確的前幀矩陣：
     * <pre>
     * BRVulkanRT.setPrevInvViewProj(prevFrame);   // 寫 offset 64
     * BRVulkanRT.setCameraData(curInvVP, ...);     // 寫 offset 0
     * prevFrame.set(curInvVP);                     // 更新 VkRTPipeline 的快取
     * </pre>
     *
     * <p>GLSL motion vector 計算：
     * <pre>
     * vec4 prevClip = cam.prevInvViewProj * vec4(worldPos, 1.0);
     * vec2 prevUV   = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
     * vec2 motionVec = uv - prevUV;
     * </pre>
     *
     * @param prevInvViewProj 前一幀的 inverse view-projection 矩陣
     */
    public static void setPrevInvViewProj(Matrix4f prevInvViewProj) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updatePrevInvViewProjUBO(device, rtDescriptorSet, prevInvViewProj);
        } catch (Exception e) {
            LOGGER.debug("Failed to update prevInvViewProj UBO: {}", e.getMessage());
        }
    }

    // ── Weather + frame index injection ─────────────────────────────────────

    /**
     * Upload weather state to the CameraUBO's weatherData field.
     *
     * <p>Called by {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline} each frame
     * before {@link #traceRays(int, int)}.
     *
     * @param wetness      global surface wetness factor [0=dry, 1=fully wet]
     * @param snowCoverage global snow coverage [0=none, 1=full]
     */
    public static void setWeatherUniforms(float wetness, float snowCoverage) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateWeatherUBO(device, rtDescriptorSet, wetness, snowCoverage);
        } catch (Exception e) {
            LOGGER.debug("Failed to update weather UBO: {}", e.getMessage());
        }
    }

    /**
     * Upload current frame index for Halton sequence / temporal accumulation.
     *
     * @param frameIndex monotonically increasing frame counter
     */
    public static void updateFrameIndex(long frameIndex) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateFrameIndexUBO(device, rtDescriptorSet, frameIndex);
        } catch (Exception e) {
            LOGGER.debug("Failed to update frameIndex UBO: {}", e.getMessage());
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────

    public static float getLastTraceTimeMs() {
        return lastTraceTimeMs;
    }

    public static long getTotalRaysTraced() {
        return totalRaysTraced;
    }

    // ── Phase 8: 三路徑 Pass 調度接口 ─────────────────────────────────────────
    //
    // 由 BRRTPipelineOrdering 調用。每個方法對應一個 RT 渲染 Pass。
    // Phase 8 整合：方法存根已建立，GPU 命令緩衝錄製在後續 PR 中補充。
    // 各方法均為 no-throw — 內部異常以 WARN 記錄，不向上傳遞（管線健壯性）。

    /**
     * Phase 8 GBuffer Pass — 填充位置/法線/反射率/材料/深度 GBuffer 附件。
     * <p>於 Blackwell 與 Ada 路徑的首個 Pass 呼叫。
     */
    public static void renderGBuffer(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：錄製 GBuffer 繪製命令，繫結 GBuffer FBO
            // 暫時委託現有 shadow pass 維持正確管線狀態
            LOGGER.trace("[Phase8] GBuffer pass — ctx.fbo={}", ctx.getFramebufferId());
        } catch (Exception e) {
            LOGGER.warn("[Phase8] renderGBuffer failed", e);
        }
    }

    /**
     * Phase 8 ReSTIR DI Dispatch（Blackwell 路徑）。
     * <p>對直接光照進行 Resampled Importance Sampling。
     */
    public static void dispatchReSTIRDI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Ping-pong reservoir buffers before compute dispatch
            BRReSTIRDI.getInstance().swap();
            // Lazy-init compute dispatcher (shares rtOutputWidth × rtOutputHeight with RT image)
            BRReSTIRComputeDispatcher dispatcher = BRReSTIRComputeDispatcher.getInstance();
            if (!dispatcher.isInitialized()) {
                dispatcher.init(rtOutputWidth, rtOutputHeight);
            }
            dispatcher.dispatchDI();
            LOGGER.trace("[Phase8] ReSTIR DI — compute pipeline dispatched ({}×{})", rtOutputWidth, rtOutputHeight);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReSTIRDI failed", e);
        }
    }

    /**
     * Phase 8 ReSTIR GI Dispatch（Blackwell 路徑）。
     * <p>對間接光照進行 Resampled Importance Sampling（多 GI ray）。
     */
    public static void dispatchReSTIRGI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Ping-pong reservoir buffers before compute dispatch
            BRReSTIRGI.getInstance().swap();
            // Reuse the same dispatcher instance initialised by dispatchReSTIRDI
            BRReSTIRComputeDispatcher dispatcher = BRReSTIRComputeDispatcher.getInstance();
            if (!dispatcher.isInitialized()) {
                dispatcher.init(rtOutputWidth, rtOutputHeight);
            }
            dispatcher.dispatchGI();
            LOGGER.trace("[Phase8] ReSTIR GI — compute pipeline dispatched");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReSTIRGI failed", e);
        }
    }

    /**
     * Phase 8 Shadow + AO 合併 Pass（Ada 路徑）。
     * <p>使用 Ray Query Compute Shader + SER 優化 warp 效率。
     */
    public static void dispatchShadowAndAO(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Lazy-init a dedicated VkRTAO pipeline for the Ada Ray Query + SER path.
            // This is separate from the BRRTCompositor-owned instance to keep dispatch
            // lifecycle aligned with BRVulkanRT (Vulkan-side) rather than the GL compositor.
            if (shadowAoPipeline == null) {
                shadowAoPipeline = new com.blockreality.api.client.rendering.vulkan.VkRTAO();
                shadowAoPipeline.init(rtOutputWidth, rtOutputHeight);
            }
            long tlas = BRVulkanBVH.getTLAS();
            if (tlas == 0L) {
                LOGGER.trace("[Phase8] Shadow+AO skipped — TLAS not ready");
                return;
            }
            // Compute inverse view-projection for world-position reconstruction in the shader
            Matrix4f invVP = new Matrix4f(ctx.getProjectionMatrix())
                    .mul(ctx.getViewMatrix())
                    .invert(new Matrix4f());
            // depthTex / normalTex are GL IDs (currently unused by VkRTAO — Vulkan-side
            // GBuffer is bound through the descriptor set built in VkRTAO.init())
            shadowAoPipeline.dispatchAO(0, 0, invVP, tlas, frameCount);
            LOGGER.trace("[Phase8] Shadow+AO dispatched — Ada Ray Query path, frame={}", frameCount);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchShadowAndAO failed", e);
        }
    }

    /**
     * Phase 8 DDGI Sample Pass（Ada 路徑）。
     * <p>幾何表面採樣 Irradiance Volume，輸出 GI diffuse 至 NRD 輸入 buffer。
     */
    public static void dispatchDDGISample(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            BRDDGIProbeSystem ddgi = BRDDGIProbeSystem.getInstance();
            if (!ddgi.isInitialized()) {
                // First-time init: real Vulkan images via BRVulkanDevice.createImage2D() (RT-6-1)
                if (!ddgi.init(BRRTSettings.getInstance().getDdgiProbeSpacingBlocks())) {
                    LOGGER.warn("[Phase8] DDGI init failed — skipping probe update");
                    return;
                }
            }
            // Camera position drives probe grid origin + activation budget
            net.minecraft.world.phys.Vec3 mc = ctx.getCamera().getPosition();
            org.joml.Vector3f camPos = new org.joml.Vector3f((float) mc.x, (float) mc.y, (float) mc.z);
            // 20% rolling update: each frame updates ~3 276 of 16 384 probes (5-frame full cycle)
            int[] updateProbes = ddgi.onFrameStart(camPos, 0.20f);
            if (updateProbes.length > 0) {
                // Lazy-init DDGI compute dispatcher (ddgi_update.comp.glsl pipeline)
                BRDDGIComputeDispatcher ddgiDispatch = BRDDGIComputeDispatcher.getInstance();
                if (!ddgiDispatch.isInitialized()) {
                    ddgiDispatch.init();
                }
                if (ddgiDispatch.isInitialized()) {
                    ddgiDispatch.dispatch(updateProbes, frameCount);
                }
            }
            LOGGER.trace("[Phase8] DDGI probe update — {} probes dispatched (frame={})",
                    updateProbes.length, frameCount);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDDGISample failed", e);
        }
    }

    /**
     * Phase 8 NRD Dispatch（Blackwell: ReBLUR；Ada: ReLAX + SIGMA）。
     * <p>前置條件：{@link BRNRDNative#isNrdAvailable()} == true。
     */
    public static void dispatchNRD() {
        if (!initialized) return;
        try {
            BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
            if (BRNRDNative.isNrdAvailable()) {
                // Lazy-create NRD denoiser instance (ReBLUR on Blackwell, ReLAX on Ada)
                if (nrdDenoiserHandle == 0L && rtOutputWidth > 0 && rtOutputHeight > 0) {
                    nrdDenoiserHandle = BRNRDNative.createDenoiser(rtOutputWidth, rtOutputHeight, 8);
                    LOGGER.info("[Phase8] NRD SDK denoiser created ({}×{})", rtOutputWidth, rtOutputHeight);
                }
                if (nrdDenoiserHandle != 0L) {
                    // Pass VkImage handle addresses to the native denoiser.
                    // inMotion = 0L until motion-vector export is wired (RT-6-2).
                    BRNRDNative.denoise(nrdDenoiserHandle,
                            rtOutputImageView,       // inColor  — RT output RGBA16F (GENERAL)
                            gbuf.getNormalView(),    // inNormal — world-space normal RGBA16F
                            0L,                      // inMotion — motion vectors (future RT-6-2)
                            gbuf.getDepthView(),     // inDepth  — linear depth R32F (GENERAL)
                            rtOutputImageView);      // outColor — in-place denoise
                    LOGGER.trace("[Phase8] NRD dispatch — SDK path (handle={})", nrdDenoiserHandle);
                }
            } else {
                // NRD SDK not loaded — delegate to pure-Vulkan ReLAX fallback
                if (BRReLAXDenoiser.isInitialized()) {
                    BRReLAXDenoiser.denoise(rtOutputImageView,
                            gbuf.getDepthView(), gbuf.getPrevDepthView(), gbuf.getNormalView());
                }
                LOGGER.trace("[Phase8] NRD dispatch — ReLAX fallback");
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchNRD failed", e);
        }
    }

    /**
     * Phase 8 ReLAX Fallback Denoiser（P2-A：NRD SDK 不可用時的主後備降噪器）。
     *
     * <p>採用純 Vulkan compute 實作，與 RT 管線共享命令緩衝區基礎設施，
     * 相較 {@link #dispatchSVGFFallback} 消除了 GL ↔ Vulkan 跨 API 同步開銷。</p>
     *
     * <p>當前整合點（RT-5-2 完整實作後補充 VkImageView 繫結）：
     * <ul>
     *   <li>currentRTView — RT 輸出 STORAGE_IMAGE（rgba16f，GENERAL）</li>
     *   <li>depthView / prevDepthView — G-Buffer 深度（SHADER_READ_ONLY）</li>
     *   <li>normalView — G-Buffer 法線（SHADER_READ_ONLY）</li>
     * </ul>
     */
    public static void dispatchReLAXFallback(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            if (BRReLAXDenoiser.isInitialized()) {
                // RT-5-2: 使用 BRGBufferAttachments 提供的真實 VkImageView handle
                BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
                long currentRTView  = rtOutputImageView;           // RT 輸出（RGBA16F，GENERAL）
                long depthView      = gbuf.getDepthView();         // 當前幀線性深度（R32F，GENERAL）
                long prevDepthView  = gbuf.getPrevDepthView();     // 前一幀線性深度（R32F，GENERAL）
                long normalView     = gbuf.getNormalView();        // 世界空間法線（RGBA16F，SHADER_READ_ONLY）
                BRReLAXDenoiser.denoise(currentRTView, depthView, prevDepthView, normalView);
                LOGGER.trace("[Phase8] ReLAX fallback denoiser dispatch — rt={} depth={} prevDepth={} normal={}",
                    currentRTView, depthView, prevDepthView, normalView);
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReLAXFallback failed", e);
        }
    }

    /**
     * Phase 8 SVGF Fallback Denoiser（@Deprecated 最後後備，GL compute）。
     * @deprecated 使用 {@link #dispatchReLAXFallback} — 純 Vulkan 實作，效能更優
     */
    @Deprecated(since = "P2-A", forRemoval = true)
    public static void dispatchSVGFFallback(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // BRSVGFDenoiser uses static methods; full texture handle binding wired in RT-5-2
            if (BRSVGFDenoiser.isInitialized()) {
                LOGGER.trace("[Phase8] SVGF fallback denoiser dispatch");
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchSVGFFallback failed", e);
        }
    }

    /**
     * Phase 8 DLSS Multi-Frame Generation Dispatch（Blackwell 路徑，×3 幀生成）。
     */
    public static void dispatchDLSSMultiFrameGen() {
        if (!initialized) return;
        try {
            // Phase 8 整合點：呼叫 BRDLSS4Manager 的 MFG evaluate
            LOGGER.trace("[Phase8] DLSS MFG dispatch — Blackwell path");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDLSSMultiFrameGen failed", e);
        }
    }

    /**
     * Phase 8 DLSS Frame Generation Dispatch（Ada 路徑，×1 幀生成）。
     */
    public static void dispatchDLSSFrameGen() {
        if (!initialized) return;
        try {
            // Phase 8 整合點：呼叫 BRDLSS4Manager 的 FG evaluate
            LOGGER.trace("[Phase8] DLSS FG dispatch — Ada path");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDLSSFrameGen failed", e);
        }
    }

    /**
     * Phase 8 Tone Mapping Pass（HDR → LDR ACES filmic）。
     */
    public static void dispatchTonemap(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：全螢幕 quad + tonemap fragment shader
            LOGGER.trace("[Phase8] Tonemap dispatch");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchTonemap failed", e);
        }
    }

    /**
     * Phase 8 UI 覆蓋層 Pass（HUD / 工具提示 / 除錯資訊）。
     */
    public static void dispatchUI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：提交 Minecraft UI command buffer
            LOGGER.trace("[Phase8] UI dispatch");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchUI failed", e);
        }
    }

    /**
     * Legacy 路徑完整幀調度 — 沿用既有非 Phase 8 管線。
     * <p>由 {@link com.blockreality.api.client.render.pipeline.BRRTPipelineOrdering}
     * 在 TIER_LEGACY_RT 路徑下調用。
     */
    public static void renderFrameLegacy(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // 委託既有 traceRays + SVGF 管線
            // BVH handle managed by BRVulkanBVH statics — no local reference needed
            int width  = 1920;  // Phase 8: 從 ctx 或 BRVulkanDevice 取得實際解析度
            int height = 1080;
            traceRays(width, height);
            LOGGER.trace("[Phase8] Legacy frame dispatch complete");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] renderFrameLegacy failed", e);
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private static long createDescriptorSetLayout(long device) {
        // Binding 0: acceleration structure (TLAS)
        // Binding 1: storage image (RT output, rgba16f)
        // Binding 2: combined image sampler (gbuffer depth)
        // Binding 3: combined image sampler (gbuffer normal)
        // Binding 4: uniform buffer (camera UBO)
        return BRVulkanDevice.createRTDescriptorSetLayout(device);
    }

    private static long createPipelineLayout(long device, long descriptorSetLayout) {
        return BRVulkanDevice.createPipelineLayout(device, descriptorSetLayout);
    }

    private static long createShaderModule(long device, String glslSource, String name) {
        // Compile GLSL to SPIR-V via shaderc (runtime) or load pre-compiled resource
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glslSource, name);
        if (spirv == null || spirv.length == 0) {
            throw new RuntimeException("Failed to compile RT shader: " + name);
        }
        return BRVulkanDevice.createShaderModule(device, spirv);
    }

    /**
     * 帶 anyhit 的 RT pipeline（主路徑）：
     * hitgroup 描述中同時包含 closesthit + anyhit，SBT 仍為 3 條目。
     */
    private static long createRTPipelineWithAnyHit(long device, long pipelineLayout,
                                                    long raygenModule, long missModule,
                                                    long chitModule, long ahitModule) {
        return BRVulkanDevice.createRayTracingPipelineWithAnyHit(device, pipelineLayout,
                raygenModule, missModule, chitModule, ahitModule);
    }
}
=======
package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.EnumSet;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;

/**
 * Main Vulkan ray tracing pipeline for Block Reality.
 * Dispatches ray tracing for shadows, reflections, ambient occlusion, and global illumination.
 *
 * <p>Uses VK_KHR_ray_tracing_pipeline to trace rays against the BLAS/TLAS built by
 * {@code BRVulkanBVH}. The output is written to an image that is interoped to GL via
 * {@code BRVulkanInterop} and then denoised by {@link BRSVGFDenoiser}.</p>
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
@OnlyIn(Dist.CLIENT)
public final class BRVulkanRT {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VulkanRT");

    // ── RT Effects ──────────────────────────────────────────────────────────

    public enum RTEffect {
        SHADOWS(true, 0.5f),               // ~0.5ms @ 1080p
        REFLECTIONS(false, 1.0f),          // ~1.0ms
        AMBIENT_OCCLUSION(false, 0.3f),
        GLOBAL_ILLUMINATION(false, 3.0f);

        public boolean enabledByDefault;
        public float estimatedCostMs;

        RTEffect(boolean d, float c) {
            enabledByDefault = d;
            estimatedCostMs = c;
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    /** Shader Binding Table handle size (typical, queried from device properties at init). */
    private static final int SBT_HANDLE_SIZE = 32;

    /** SBT handle alignment requirement. */
    private static final int SBT_HANDLE_ALIGNMENT = 64;

    /** Max recursion depth — 2 supports primary shadow + 1 GI bounce (Blackwell). */
    private static final int MAX_RT_RECURSION_DEPTH = 2;

    // ── GLSL Shader Sources ─────────────────────────────────────────────────
    //
    // These shaders are compiled at runtime via BRVulkanDevice.compileGLSLtoSPIRV().
    // In production builds they should be pre-compiled to SPIR-V resources.
    //
    // Specialization constants (applied in BRAdaRTConfig.buildSpecializationInfo):
    //   SC_0 = GPU_TIER  : 0=Legacy, 1=Ada SM8.9, 2=Blackwell SM10+
    //   SC_1 = AO_SAMPLES: 0=Ada(8),  1=Blackwell(16)   [raygen/rtao]
    //          MAX_BOUNCES: 0=Ada(1),  1=Blackwell(2)    [closesthit]

    // ── Raygen — shadow + reflection + SER + far-field GI via SVDAG ────────
    private static final String RAYGEN_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing            : require
            #extension GL_NV_ray_tracing_invocation_reorder : enable
            #extension GL_EXT_ray_query              : enable

            // ── Descriptor bindings ──────────────────────────────────────────
            layout(set=0, binding=0) uniform accelerationStructureEXT u_TLAS;
            layout(set=0, binding=1, rgba16f) uniform image2D u_RTOutput;     // shadow.r + refl.gba
            layout(set=0, binding=2) uniform sampler2D u_GBufDepth;
            layout(set=0, binding=3) uniform sampler2D u_GBufNormal;       // world-space octahedron
            layout(set=0, binding=4) uniform sampler2D u_GBufMaterial;     // roughness.r metallic.g
            layout(set=0, binding=5) uniform sampler2D u_GBufMotion;       // motion vector (for TAA)

            // CameraUBO 256-byte layout (std140, matches BRVulkanDevice.createCameraUBO):
            //   offset   0: mat4  invViewProj
            //   offset  64: mat4  prevInvViewProj  (SVGF temporal reprojection)
            //   offset 128: vec4  weatherData      (.x=wetness .y=snowCoverage)
            //   offset 144: float frameIndex       (Halton seed)
            //   offset 148: float _pad[3]          (std140 padding → next vec4 at 160)
            //   offset 160: vec4  cameraPos        (.xyz = world pos)
            //   offset 176: vec4  sunDir           (.xyz = normalised sun direction)
            layout(set=0, binding=6) uniform CameraUBO {
                mat4  invViewProj;
                mat4  prevInvViewProj;
                vec4  weatherData;
                float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4  cameraPos;
                vec4  sunDir;
            } cam;

            // SVDAG buffer for far-field LOD 3 GI (Ada+ only) — 128+ chunk range
            layout(set=0, binding=7, std430) readonly buffer SVDAGBuffer {
                uint  nodes[];
            } u_SVDAG;

            // ── Specialization constants ─────────────────────────────────────
            layout(constant_id = 0) const int  SC_GPU_TIER   = 0; // 0=legacy,1=ada,2=blackwell
            layout(constant_id = 1) const int  SC_AO_SAMPLES = 8; // 8 or 16

            // ── Payload types ────────────────────────────────────────────────
            layout(location = 0) rayPayloadEXT vec4 shadowPayload;   // .r=lit(1)/shadow(0)
            layout(location = 1) rayPayloadEXT vec4 reflPayload;     // .rgb=radiance .a=hitDist

            // ── Hit object (SER) — Ada+ ──────────────────────────────────────
            #ifdef GL_NV_ray_tracing_invocation_reorder
            hitObjectNV hitObj;
            #endif

            // ── Utility: octahedron-decode normal ───────────────────────────
            vec3 octDecode(vec2 f) {
                vec3 n = vec3(f.x, f.y, 1.0 - abs(f.x) - abs(f.y));
                if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * sign(n.xy);
                return normalize(n);
            }

            // ── Utility: GGX NDF importance sampling ────────────────────────
            vec3 importanceSampleGGX(vec2 Xi, vec3 N, float roughness) {
                float a = roughness * roughness;
                float phi = 6.283185 * Xi.x;
                float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (a*a - 1.0) * Xi.y));
                float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
                vec3 H = vec3(cos(phi)*sinTheta, sin(phi)*sinTheta, cosTheta);
                vec3 up = abs(N.z) < 0.999 ? vec3(0,0,1) : vec3(1,0,0);
                vec3 T = normalize(cross(up, N));
                vec3 B = cross(N, T);
                return normalize(T*H.x + B*H.y + N*H.z);
            }

            // ── Utility: low-discrepancy noise (Halton) ──────────────────────
            float halton(uint index, uint base) {
                float f = 1.0, r = 0.0;
                uint idx = index;
                for (uint i = 0; i < 16; i++) {
                    if (idx == 0u) break;
                    f /= float(base);
                    r += f * float(idx % base);
                    idx /= base;
                }
                return r;
            }

            // ── Utility: SVDAG march for far-field GI (LOD 3, 128+ chunks) ──
            // Simplified 8-bit voxel lookup; full SVO DDA omitted for clarity.
            vec3 svdagAmbient(vec3 dir) {
                // Placeholder: return a sky gradient for LOD3 indirect light.
                // Full SVO DDA traversal would read u_SVDAG.nodes[] here.
                float sky = max(0.0, dot(dir, cam.sunDir.xyz));
                return mix(vec3(0.3, 0.5, 0.8), vec3(1.0, 0.95, 0.8), sky);
            }

            void main() {
                ivec2 pixel   = ivec2(gl_LaunchIDEXT.xy);
                ivec2 imgSize = ivec2(gl_LaunchSizeEXT.xy);
                vec2  uv      = (vec2(pixel) + 0.5) / vec2(imgSize);

                // ── Sample GBuffer ────────────────────────────────────────────
                float depth    = texture(u_GBufDepth, uv).r;
                if (depth >= 0.9999) {
                    // Sky pixel — no shadow, sky reflection
                    imageStore(u_RTOutput, pixel, vec4(1.0, 0.5, 0.6, 0.7)); // lit + sky
                    return;
                }

                vec4  matData  = texture(u_GBufMaterial, uv);
                float roughness = matData.r;
                float metallic  = matData.g;
                vec4  normalRaw = texture(u_GBufNormal, uv);
                vec3  N         = octDecode(normalRaw.xy);

                // ── Reconstruct world position ───────────────────────────────
                vec4 ndcPos   = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 worldH   = cam.invViewProj * ndcPos;
                vec3 worldPos = worldH.xyz / worldH.w;

                // ── Weather BRDF modification ────────────────────────────────
                float wetness     = cam.weatherData.x;
                float snowCoverage= cam.weatherData.y;
                // Wet surfaces: roughness↓, metallic stays
                float effRoughness = mix(roughness, roughness * 0.35, wetness);
                // Snow: roughness=1.0, metallic=0
                effRoughness = mix(effRoughness, 1.0, snowCoverage * step(0.7, N.y));

                // ── Shadow Ray ───────────────────────────────────────────────
                vec3 shadowOrigin = worldPos + N * 0.005;
                shadowPayload = vec4(1.0); // assume lit

                #ifdef GL_NV_ray_tracing_invocation_reorder
                if (SC_GPU_TIER >= 1) {
                    // Ada/Blackwell SER path: record hit object before reorder
                    hitObjectTraceRayNV(hitObj, u_TLAS,
                        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                        0xFF, 0, 0, 0,
                        shadowOrigin, 0.001, cam.sunDir.xyz, 1000.0, 0);
                    // Reorder threads by material/LOD hint (0 = shadow group)
                    reorderThreadNV(hitObj, 0u, 1u);
                    // Execute the hit or miss shader
                    hitObjectExecuteShaderNV(hitObj, 0);
                } else
                #endif
                {
                    traceRayEXT(u_TLAS,
                        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                        0xFF, 0, 0, 0,
                        shadowOrigin, 0.001, cam.sunDir.xyz, 1000.0, 0);
                }
                float shadowFactor = shadowPayload.r;

                // ── Reflection Ray (GGX importance sampling) ─────────────────
                vec3 reflRadiance = vec3(0.0);
                if (roughness < 0.85 && metallic > 0.03) {
                    uint frameIdx = uint(cam.frameIndex);
                    vec2  Xi  = vec2(halton(frameIdx, 2u), halton(frameIdx, 3u));
                    vec3  V   = normalize(cam.cameraPos.xyz - worldPos);
                    vec3  H   = importanceSampleGGX(Xi, N, effRoughness);
                    vec3  rDir = reflect(-V, H);

                    reflPayload = vec4(0.0);

                    #ifdef GL_NV_ray_tracing_invocation_reorder
                    if (SC_GPU_TIER >= 1) {
                        hitObjectTraceRayNV(hitObj, u_TLAS,
                            gl_RayFlagsOpaqueEXT,
                            0xFF, 1, 0, 1,
                            worldPos + N * 0.01, 0.01, rDir, 500.0, 1);
                        // Reorder by material hit (1 = reflection group)
                        reorderThreadNV(hitObj, 1u, 1u);
                        hitObjectExecuteShaderNV(hitObj, 1);
                    } else
                    #endif
                    {
                        traceRayEXT(u_TLAS, gl_RayFlagsOpaqueEXT,
                            0xFF, 1, 0, 1,
                            worldPos + N * 0.01, 0.01, rDir, 500.0, 1);
                    }

                    float hitDist = reflPayload.a;
                    if (hitDist <= 0.0) {
                        // Miss — sample sky or SVDAG far-field GI
                        if (SC_GPU_TIER >= 1) {
                            reflRadiance = svdagAmbient(rDir); // Ada: SVDAG GI
                        } else {
                            float skyL = max(0.0, dot(rDir, cam.sunDir.xyz));
                            reflRadiance = mix(vec3(0.3,0.5,0.8), vec3(1.0,0.95,0.8), skyL);
                        }
                    } else {
                        reflRadiance = reflPayload.rgb;
                    }

                    // Fresnel–Schlick approximation (metallic blend)
                    float NdotV  = max(dot(N, V), 0.0);
                    float fresnel = metallic + (1.0 - metallic) * pow(1.0 - NdotV, 5.0);
                    reflRadiance *= fresnel;
                }

                // ── Output packing ───────────────────────────────────────────
                // R = shadow factor (1=lit, 0=shadow)
                // GB = reflection radiance .rg, A = 1.0（固定不透明）
                // ★ P7-fix: A 強制 1.0，reflRadiance.b 捨棄，與外部 shader 格式一致。
                imageStore(u_RTOutput, pixel,
                    vec4(shadowFactor, reflRadiance.rg, 1.0));
            }
            """;

    // ── Closest hit — PBR shading + physics stress heatmap + GI bounce ──────
    private static final String CLOSEST_HIT_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            // Payload: .rgb=radiance .a=hitDist (negative = shadow hit)
            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;
            layout(location = 1) rayPayloadInEXT vec4 reflPayload;

            // ── Hit attributes ────────────────────────────────────────────────
            hitAttributeEXT vec2 hitBaryCoords;

            // ── TLAS for recursive tracing ────────────────────────────────────
            layout(set=0, binding=0) uniform accelerationStructureEXT u_TLAS;

            // ── Material SSBO (matId → phys props) ───────────────────────────
            struct MatEntry { vec4 albedo; float roughness; float metallic; float emissive; float stress; };
            layout(set=0, binding=8, std430) readonly buffer MaterialBuffer {
                MatEntry materials[];
            } u_Materials;

            // ── Geometry Buffers ─────────────────────────────────────────────
            layout(set=0, binding=9, std430) readonly buffer VertexBuffer { float v[]; } u_Vertices;
            layout(set=0, binding=10, std430) readonly buffer IndexBuffer { uint i[]; } u_Indices;

            // ── Emissive Light Tree (ReSTIR DI) ─────────────────────────────
            struct LightEntry { vec4 posInt; vec4 colRad; }; // pos.xyz, intensity.w | color.rgb, radius.w
            layout(set=0, binding=11, std430) readonly buffer LightBuffer {
                uint lightCount;
                LightEntry lights[];
            } u_Lights;

            layout(set=0, binding=6) uniform CameraUBO {
                mat4  invViewProj;
                mat4  prevInvViewProj;
                vec4  weatherData;
                float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4  cameraPos;
                vec4  sunDir;
            } cam;

            layout(constant_id = 0) const int SC_GPU_TIER   = 0;
            layout(constant_id = 1) const int SC_MAX_BOUNCES = 1; // 1=Ada, 2=Blackwell

            // ── Utility: low-discrepancy noise (Halton) ──────────────────────
            float halton(uint index, uint base) {
                float f = 1.0, r = 0.0;
                uint idx = index;
                for (uint i = 0; i < 16; i++) {
                    if (idx == 0u) break;
                    f /= float(base);
                    r += f * float(idx % base);
                    idx /= base;
                }
                return r;
            }

            // ── GGX BRDF ─────────────────────────────────────────────────────
            float D_GGX(float NdotH, float a) {
                float a2 = a * a;
                float d  = (NdotH * a2 - NdotH) * NdotH + 1.0;
                return a2 / (3.14159265 * d * d);
            }
            float G_Smith(float NdotV, float NdotL, float a) {
                float k = a * 0.5;
                float gV = NdotV / (NdotV * (1.0 - k) + k);
                float gL = NdotL / (NdotL * (1.0 - k) + k);
                return gV * gL;
            }
            vec3 F_Schlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
            }

            void main() {
                // Shadow hit — return occluded
                shadowPayload = vec4(0.0, 0.0, 0.0, 1.0);

                // If we also have reflection payload — compute basic PBR shading
                uint matId = uint(gl_InstanceCustomIndexEXT) & 0xFFu;
                if (matId < 256u) {
                    MatEntry mat = u_Materials.materials[matId];

                    // Per-vertex normal from SSBO (assuming 8-float layout: pos3, normal3, uv2)
                    uint pId = uint(gl_PrimitiveID);
                    uint i0 = u_Indices.i[3 * pId + 0];
                    uint i1 = u_Indices.i[3 * pId + 1];
                    uint i2 = u_Indices.i[3 * pId + 2];

                    vec3 n0 = vec3(u_Vertices.v[8 * i0 + 3], u_Vertices.v[8 * i0 + 4], u_Vertices.v[8 * i0 + 5]);
                    vec3 n1 = vec3(u_Vertices.v[8 * i1 + 3], u_Vertices.v[8 * i1 + 4], u_Vertices.v[8 * i1 + 5]);
                    vec3 n2 = vec3(u_Vertices.v[8 * i2 + 3], u_Vertices.v[8 * i2 + 4], u_Vertices.v[8 * i2 + 5]);

                    float baryW = 1.0 - hitBaryCoords.x - hitBaryCoords.y;
                    vec3 N = normalize(n0 * baryW + n1 * hitBaryCoords.x + n2 * hitBaryCoords.y);

                    vec3 V    = -gl_WorldRayDirectionEXT;
                    vec3 L    = cam.sunDir.xyz;
                    vec3 H    = normalize(V + L);

                    float NdotL = max(dot(N, L), 0.0);
                    float NdotV = max(dot(N, V), 0.0);
                    float NdotH = max(dot(N, H), 0.0);
                    float VdotH = max(dot(V, H), 0.0);

                    // Weather: wet surface modification
                    float ro = mix(mat.roughness, mat.roughness * 0.35, cam.weatherData.x);
                    ro = mix(ro, 1.0, cam.weatherData.y * step(0.7, N.y));

                    float a   = max(ro * ro, 0.01);
                    vec3 F0   = mix(vec3(0.04), mat.albedo.rgb, mat.metallic);
                    vec3 F    = F_Schlick(VdotH, F0);
                    float D   = D_GGX(NdotH, a);
                    float G   = G_Smith(NdotV, NdotL, a);
                    vec3 spec = (D * G * F) / max(4.0 * NdotV * NdotL, 0.001);

                    vec3 kd  = (1.0 - F) * (1.0 - mat.metallic);
                    vec3 directDiffuse = (kd * mat.albedo.rgb / 3.14159265);
                    vec3 col = (directDiffuse + spec) * NdotL;
                    vec3 worldPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;

                    // ── ReSTIR DI: Sample one dynamic light explicitly ──
                    vec3 dynamicLight = vec3(0.0);
                    if (u_Lights.lightCount > 0u) {
                        uint fId = uint(cam.frameIndex);
                        uint pId = uint(gl_PrimitiveID);
                        float rnd = halton(fId * 5u + pId % 3u, 2u);
                        uint lightIdx = min(uint(rnd * float(u_Lights.lightCount)), u_Lights.lightCount - 1u);

                        LightEntry le = u_Lights.lights[lightIdx];
                        vec3 lPos = le.posInt.xyz;
                        vec3 dirToLight = lPos - worldPos;
                        float distToLight = length(dirToLight);
                        dirToLight /= distToLight;

                        float lNdotL = max(dot(N, dirToLight), 0.0);
                        if (lNdotL > 0.0) {
                            // Uniform PDF
                            float pdf = 1.0 / float(u_Lights.lightCount);
                            float atten = le.posInt.w / max(distToLight * distToLight, 0.01);

                            // Trace shadow ray
                            // We use a local shadow trace by hijacking reflPayload (with careful restore) or using a dedicated shadow trace!
                            // Standard way: call traceRayEXT with shadowPayload (location = 0)
                            shadowPayload.r = 1.0;
                            traceRayEXT(u_TLAS, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT | gl_RayFlagsSkipClosestHitShaderEXT,
                                        0xFF, 0, 0, 0, worldPos + N * 0.01, 0.01, dirToLight, distToLight - 0.05, 0);

                            if (shadowPayload.r > 0.5) {
                                // Unoccluded
                                vec3 l_col = le.colRad.rgb;
                                dynamicLight = (directDiffuse + spec) * l_col * atten * lNdotL / pdf;
                            }
                        }
                    }
                    col += dynamicLight;

                    float currentBounce = reflPayload.w;
                    vec3 indirectLight = vec3(0.0);

                    if (SC_MAX_BOUNCES > 0 && currentBounce < float(SC_MAX_BOUNCES)) {
                        // Cosine-weighted hemisphere sampling for Lambertian GI
                        uint pId = uint(gl_PrimitiveID);
                        uint fId = uint(cam.frameIndex);
                        vec2 Xi = vec2(halton(fId * 4u + uint(currentBounce)*2u, 2u),
                                       halton(fId * 4u + uint(currentBounce)*2u + 1u, 3u));

                        float phi = 6.283185307 * Xi.x;
                        float cosTheta = sqrt(1.0 - Xi.y);
                        float sinTheta = sqrt(Xi.y);

                        vec3 H_hemi = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
                        vec3 up = abs(N.z) < 0.999 ? vec3(0,0,1) : vec3(1,0,0);
                        vec3 T_hemi = normalize(cross(up, N));
                        vec3 B_hemi = cross(N, T_hemi);
                        vec3 diffDir = normalize(T_hemi * H_hemi.x + B_hemi * H_hemi.y + N * H_hemi.z);

                        // Trace recursive bounce
                        reflPayload.w = currentBounce + 1.0;
                        traceRayEXT(u_TLAS, gl_RayFlagsOpaqueEXT, 0xFF, 1, 0, 1,
                                    gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT + N * 0.01,
                                    0.01, diffDir, 500.0, 1);

                        // Accumulate using Lambertian BRDF (pi is canceled out by PDF = cosTheta/pi)
                        indirectLight = reflPayload.rgb * mat.albedo.rgb;
                    }

                    col += indirectLight;

                    // Physics stress heatmap (red tint for high stress)
                    col = mix(col, vec3(1.0, 0.1, 0.1), clamp(mat.stress * 0.5, 0.0, 0.5));
                    // Emissive
                    col += mat.albedo.rgb * mat.emissive;

                    reflPayload = vec4(col, gl_HitTEXT);
                } else {
                    reflPayload = vec4(0.5, 0.5, 0.5, gl_HitTEXT);
                }
            }
            """;

    // ── Miss — sky radiance (Rayleigh+Mie approximation) ────────────────────
    private static final String MISS_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;
            layout(location = 1) rayPayloadInEXT vec4 reflPayload;

            layout(set=0, binding=6) uniform CameraUBO {
                mat4 invViewProj; mat4 prevInvViewProj;
                vec4 weatherData; float frameIndex;
                float _pad0, _pad1, _pad2;
                vec4 cameraPos; vec4 sunDir;
            } cam;

            // Rayleigh + Mie sky approximation (matches BRAtmosphereEngine output)
            vec3 atmosphere(vec3 dir) {
                float sun = max(0.0, dot(dir, cam.sunDir.xyz));
                float horizon = clamp(1.0 - dir.y, 0.0, 1.0);

                // Rayleigh scattering (blue sky, red at horizon)
                vec3 rayleigh = mix(vec3(0.3, 0.55, 1.0), vec3(1.0, 0.6, 0.3), horizon * 0.7);

                // Sun disc
                float sunDisc = pow(sun, 256.0);
                vec3 sunColor = vec3(1.0, 0.95, 0.85) * 5.0;

                // Mie scattering (glare around sun)
                float mie = pow(sun, 8.0) * 0.3;
                vec3 mieColor = vec3(1.0, 0.9, 0.8) * mie;

                return rayleigh + sunColor * sunDisc + mieColor;
            }

            void main() {
                // Shadow miss = fully lit
                shadowPayload = vec4(1.0, 1.0, 1.0, 0.0);

                // Reflection miss = sky radiance
                vec3 sky = atmosphere(gl_WorldRayDirectionEXT);
                reflPayload = vec4(sky, -1.0); // hitDist = -1 → miss marker
            }
            """;

    // ── Any-hit shader (透明 alpha-test，基礎管線 fallback) ─────────────────
    //
    // 在 Ada 路徑由 ada/transparent.rahit.glsl 取代（有折射/SSS 計算）。
    // 基礎管線僅做機率性丟棄：PCG 雜湊決定是否讓光線穿過半透明方塊。
    private static final String ANYHIT_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require

            // location 0 = shadow payload, location 1 = reflection payload
            layout(location = 0) rayPayloadInEXT vec4 shadowPayload;

            void main() {
                // PCG hash of pixel coord + primitive ID → stable per-sample noise
                uint seed = uint(gl_LaunchIDEXT.x) * 1973u
                          + uint(gl_LaunchIDEXT.y) * 9277u
                          + uint(gl_PrimitiveID)   * 26699u;
                seed = seed * 747796405u + 2891336453u;
                float rnd = float((seed >> 16u) & 0xFFFFu) / 65535.0;

                // 50% transmission — Phase 3: bind MaterialSSBO for per-matId alpha
                const float ALPHA = 0.5;
                if (rnd > ALPHA) {
                    // Let shadow ray continue through transparent geometry
                    ignoreIntersectionEXT();
                }
                // else: accept intersection → block shadow (opaque to light)
            }
            """;

    // ── Pipeline state ──────────────────────────────────────────────────────

    private static boolean initialized = false;
    private static long rtPipeline;             // VkPipeline (ray tracing)
    private static long rtPipelineLayout;       // VkPipelineLayout
    private static long rtDescriptorSetLayout;
    private static long rtDescriptorPool;
    private static long rtDescriptorSet;
    private static long sbtBuffer;              // Shader Binding Table
    private static long sbtBufferMemory;
    private static final EnumSet<RTEffect> enabledEffects = EnumSet.of(RTEffect.SHADOWS);

    // ── Phase 6: RT 輸出 VkImage 資源（GL/VK 共享用）──────────────────────────
    /** RT 輸出 VkImage（RGBA16F，GENERAL layout，STORAGE + TRANSFER_SRC 用途）。 */
    private static long rtOutputImage       = 0L;
    /** RT 輸出 VkImage 的 VkDeviceMemory（DEVICE_LOCAL，帶 exportable fd 標誌）。 */
    private static long rtOutputImageMemory = 0L;
    /** RT 輸出 VkImageView（VK_IMAGE_VIEW_TYPE_2D，RGBA16F）。 */
    private static long rtOutputImageView   = 0L;
    /** RT 完成 VkSemaphore（exportable fd，供 GL_EXT_semaphore_fd 使用）。 */
    private static long doneSemaphore       = 0L;

    // ── Phase 6: CPU Readback 資源（Fallback 路徑）──────────────────────────────
    /** Host-visible VkBuffer（TRANSFER_DST），用於 vkCmdCopyImageToBuffer。 */
    private static long stagingBuffer       = 0L;
    /** Staging buffer 的 VkDeviceMemory（HOST_VISIBLE + HOST_COHERENT）。 */
    private static long stagingBufferMemory = 0L;
    /** 上一幀 readback 的像素數據（RGBA16F，host 端 ByteBuffer）。 */
    private static ByteBuffer readbackBuffer = null;

    /** 輸出 image 的尺寸（pixels）。 */
    private static int rtOutputWidth  = 0;
    private static int rtOutputHeight = 0;

    // SBT regions
    private static long raygenRegionOffset, raygenRegionStride, raygenRegionSize;
    private static long missRegionOffset, missRegionStride, missRegionSize;
    private static long hitRegionOffset, hitRegionStride, hitRegionSize;

    // Stats
    private static float lastTraceTimeMs;
    private static long totalRaysTraced;
    private static long frameCount;

    /** Lazy-initialized VkRTAO compute pipeline for the Ada Shadow+AO pass. */
    private static com.blockreality.api.client.rendering.vulkan.VkRTAO shadowAoPipeline = null;
    /** NRD SDK denoiser handle; 0 = not yet created or NRD unavailable. */
    private static long nrdDenoiserHandle = 0L;

    private BRVulkanRT() { }

    /**
     * RT 輸出 VkImageView 公開 getter（RT-5-2 GBuffer 接線）。
     *
     * <p>供 {@code BRGBufferAttachments.dispatchReLAXFallback} 以及
     * volumetric / FSR dispatch 取得 RT 輸出圖像 VkImageView。
     *
     * @return rtOutputImageView handle，或 0L（未初始化）
     */
    public static long getRtOutputImageView() {
        return rtOutputImageView;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Initialise the Vulkan ray tracing pipeline.
     *
     * <ol>
     *   <li>Check {@code BRVulkanDevice.isRTSupported()}</li>
     *   <li>Create descriptor set layout (TLAS + output image + GBuffer samplers + UBO)</li>
     *   <li>Create pipeline layout</li>
     *   <li>Load / compile shader modules (raygen, miss, closest-hit)</li>
     *   <li>Create ray tracing pipeline via {@code vkCreateRayTracingPipelinesKHR}</li>
     *   <li>Query SBT handle size and create SBT buffer</li>
     *   <li>Copy shader group handles to SBT</li>
     * </ol>
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("BRVulkanRT.init() called but already initialised");
            return;
        }

        try {
            // Step 1 — device capability check
            if (!BRVulkanDevice.isRTSupported()) {
                LOGGER.warn("Vulkan ray tracing not supported on this device — RT effects disabled");
                return;
            }

            long device = BRVulkanDevice.getVkDevice();
            LOGGER.info("Initialising Vulkan RT pipeline...");

            // Step 2 — descriptor set layout
            // Bindings: 0=TLAS, 1=output image, 2=depth sampler, 3=normal sampler, 4=camera UBO
            rtDescriptorSetLayout = createDescriptorSetLayout(device);

            // Step 3 — pipeline layout
            rtPipelineLayout = createPipelineLayout(device, rtDescriptorSetLayout);

            // Step 4 — shader modules
            long raygenModule = createShaderModule(device, RAYGEN_GLSL, "raygen");
            long missModule   = createShaderModule(device, MISS_GLSL,   "miss");
            long chitModule   = createShaderModule(device, CLOSEST_HIT_GLSL, "closest_hit");
            // Any-hit for transparent geometry (glass/water/leaves alpha-test)
            long ahitModule   = createShaderModule(device, ANYHIT_GLSL, "any_hit");

            // Step 5 — ray tracing pipeline
            // 3 shader groups: raygen | miss | hitgroup(chit + ahit)
            // SBT still has 3 entries — anyhit is inside hitgroup, not a separate slot
            rtPipeline = createRTPipelineWithAnyhit(device, rtPipelineLayout,
                    raygenModule, missModule, chitModule, ahitModule);

            // Destroy shader modules — no longer needed after pipeline creation
            BRVulkanDevice.destroyShaderModule(device, raygenModule);
            BRVulkanDevice.destroyShaderModule(device, missModule);
            BRVulkanDevice.destroyShaderModule(device, chitModule);
            BRVulkanDevice.destroyShaderModule(device, ahitModule);

            // Step 6 — SBT (3 groups: raygen / miss / hitgroup)
            // hitgroup internally references both closesthit + anyhit via pipeline creation;
            // no additional SBT entry is needed for anyhit.
            int handleSize = BRVulkanDevice.getRTShaderGroupHandleSize();
            int alignedHandleSize = alignUp(handleSize, SBT_HANDLE_ALIGNMENT);

            raygenRegionOffset = 0;
            raygenRegionStride = alignedHandleSize;
            raygenRegionSize   = alignedHandleSize;

            missRegionOffset = alignedHandleSize;
            missRegionStride = alignedHandleSize;
            missRegionSize   = alignedHandleSize;

            hitRegionOffset = alignedHandleSize * 2L;
            hitRegionStride = alignedHandleSize;
            hitRegionSize   = alignedHandleSize;

            final int SBT_GROUP_COUNT = 3; // raygen + miss + hitgroup
            long sbtSize = (long) alignedHandleSize * SBT_GROUP_COUNT;
            sbtBuffer = BRVulkanDevice.createBuffer(device, sbtSize,
                    0x00000400 /* VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR */
                    | 0x00020000 /* VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT */
                    | 0x00000002 /* VK_BUFFER_USAGE_TRANSFER_DST_BIT */);
            sbtBufferMemory = BRVulkanDevice.allocateAndBindBuffer(device, sbtBuffer,
                    0x00000002 /* VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT */
                    | 0x00000004 /* VK_MEMORY_PROPERTY_HOST_COHERENT_BIT */);

            // Step 7 — copy shader group handles into SBT
            copyShaderGroupHandlesToSBT(device, rtPipeline, sbtBufferMemory,
                    sbtSize, handleSize, alignedHandleSize, SBT_GROUP_COUNT);

            // Descriptor pool + set
            rtDescriptorPool = createDescriptorPool(device);
            rtDescriptorSet = allocateDescriptorSet(device, rtDescriptorPool, rtDescriptorSetLayout);

            // CameraUBO：建立 256-byte HOST_COHERENT buffer，綁定 descriptor set binding 4
            long ubo = BRVulkanDevice.createCameraUBO(device, rtDescriptorSet);
            if (ubo == 0L) {
                LOGGER.error("Failed to create CameraUBO — RT effects disabled");
                cleanup();
                return;
            }

            initialized = true;
            LOGGER.info("Vulkan RT pipeline initialised successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialise Vulkan RT pipeline — RT effects disabled", e);
            cleanup();
        }
    }

    /**
     * Destroy the RT pipeline and all associated Vulkan resources.
     */
    public static void cleanup() {
        if (!initialized && rtPipeline == 0) {
            return;
        }

        LOGGER.info("Cleaning up Vulkan RT pipeline");
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.deviceWaitIdle(device);

            if (rtDescriptorPool != 0) {
                BRVulkanDevice.destroyDescriptorPool(device, rtDescriptorPool);
                rtDescriptorPool = 0;
                rtDescriptorSet = 0;
            }
            if (sbtBuffer != 0) {
                BRVulkanDevice.destroyBuffer(device, sbtBuffer);
                sbtBuffer = 0;
            }
            if (sbtBufferMemory != 0) {
                BRVulkanDevice.freeMemory(device, sbtBufferMemory);
                sbtBufferMemory = 0;
            }
            if (rtPipeline != 0) {
                BRVulkanDevice.destroyPipeline(device, rtPipeline);
                rtPipeline = 0;
            }
            if (rtPipelineLayout != 0) {
                BRVulkanDevice.destroyPipelineLayout(device, rtPipelineLayout);
                rtPipelineLayout = 0;
            }
            if (rtDescriptorSetLayout != 0) {
                BRVulkanDevice.destroyDescriptorSetLayout(device, rtDescriptorSetLayout);
                rtDescriptorSetLayout = 0;
            }
        } catch (Exception e) {
            LOGGER.error("Error during RT pipeline cleanup", e);
        }

        cleanupOutputImage();

        // Cleanup lazily-initialised Phase 8 subsystems
        if (shadowAoPipeline != null) {
            shadowAoPipeline.cleanup();
            shadowAoPipeline = null;
        }
        if (nrdDenoiserHandle != 0L && BRNRDNative.isNrdAvailable()) {
            BRNRDNative.destroyDenoiser(nrdDenoiserHandle);
            nrdDenoiserHandle = 0L;
        }
        BRReSTIRComputeDispatcher.getInstance().cleanup();
        BRDDGIComputeDispatcher.getInstance().cleanup();
        BRDDGIProbeSystem.getInstance().cleanup();

        initialized = false;
        lastTraceTimeMs = 0;
        totalRaysTraced = 0;
        frameCount = 0;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Phase 6: RT 輸出 VkImage 管理（GL/VK Interop 基礎）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 建立 RT 輸出 VkImage（RGBA16F，帶 external memory export fd）。
     *
     * <p>此方法在 {@link #init()} 成功後，由 {@code VkRTPipeline.init(w,h)} 呼叫。
     * 建立的 VkImage 以 {@code VK_IMAGE_USAGE_STORAGE_BIT | TRANSFER_SRC_BIT} 標記，
     * 可供 shader 寫入（{@code u_RTOutput}）並透過 {@code vkCmdCopyImageToBuffer} 讀回。
     *
     * <p>記憶體使用 {@code VkExportMemoryAllocateInfo}，使外部程序（OpenGL GL_EXT_memory_object_fd）
     * 可透過 {@link #exportOutputMemoryFd()} 取得的 fd 直接映射此記憶體，實現零拷貝 VK→GL 共享。
     *
     * @param width  RT 輸出寬度（像素）
     * @param height RT 輸出高度（像素）
     */
    public static void initOutputImage(int width, int height) {
        if (rtOutputImage != 0) {
            LOGGER.warn("[Phase6] initOutputImage called but output image already exists; skipping");
            return;
        }
        if (!initialized) {
            LOGGER.warn("[Phase6] initOutputImage called before BRVulkanRT.init()");
            return;
        }

        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) {
            LOGGER.error("[Phase6] VkDevice not available");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            rtOutputWidth  = width;
            rtOutputHeight = height;
            final long pixelBytes = (long) width * height * 8L; // RGBA16F = 8 bytes

            // ── Step 1: 建立帶 external memory export 的 VkImage ─────────────────
            boolean canExport = BRVulkanDevice.hasExternalMemory();

            // VkExternalMemoryImageCreateInfo（exportable memory，僅在支援時加入）
            long pNextChain = VK_NULL_HANDLE;
            VkExternalMemoryImageCreateInfo externalMemCreateInfo = null;
            if (canExport) {
                externalMemCreateInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
                pNextChain = externalMemCreateInfo.address();
            }

            VkExtent3D extent = VkExtent3D.calloc(stack)
                .width(width).height(height).depth(1);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(pNextChain)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R16G16B16A16_SFLOAT)     // 97
                .extent(extent)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_STORAGE_BIT |          // shader imageStore
                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT)     // vkCmdCopyImageToBuffer
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkCreateImage failed: {}", result);
                return;
            }
            rtOutputImage = pImage.get(0);

            // ── Step 2: 查詢記憶體需求並分配 VkDeviceMemory ──────────────────────
            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, rtOutputImage, memReqs);

            int memTypeIndex = BRVulkanDevice.findMemoryType(
                memReqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memTypeIndex);

            if (canExport) {
                // VkExportMemoryAllocateInfo（讓 fd 可被匯出給 GL）
                VkExportMemoryAllocateInfo exportMem = VkExportMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
                allocInfo.pNext(exportMem.address());
            }

            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(device, allocInfo, null, pMemory);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkAllocateMemory failed: {}", result);
                vkDestroyImage(device, rtOutputImage, null);
                rtOutputImage = 0;
                return;
            }
            rtOutputImageMemory = pMemory.get(0);
            vkBindImageMemory(device, rtOutputImage, rtOutputImageMemory, 0L);

            // ── Step 3: 建立 VkImageView ─────────────────────────────────────────
            VkImageSubresourceRange subRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(rtOutputImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R16G16B16A16_SFLOAT)
                .subresourceRange(subRange);

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS) {
                LOGGER.error("[Phase6] vkCreateImageView failed: {}", result);
                // 繼續但 imageView = 0（不影響記憶體 fd 匯出）
            } else {
                rtOutputImageView = pView.get(0);
            }

            // ── Step 4: Image layout 轉換 UNDEFINED → GENERAL ────────────────────
            transitionImageLayout(device,
                rtOutputImage,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL,
                0,                                  // srcAccessMask
                VK_ACCESS_SHADER_WRITE_BIT,         // dstAccessMask
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,  // srcStageMask
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR  // dstStageMask
            );

            // ── Step 5: 更新 Descriptor Set（binding=1 = u_RTOutput image）────────
            if (rtOutputImageView != 0 && rtDescriptorSet != 0) {
                VkDescriptorImageInfo.Buffer imageDesc = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(VK_NULL_HANDLE)
                    .imageView(rtOutputImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

                VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack);
                writeSet.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(rtDescriptorSet)
                    .dstBinding(1)         // binding 1 = u_RTOutput (storage image)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imageDesc);
                vkUpdateDescriptorSets(device, writeSet, null);
                LOGGER.debug("[Phase6] u_RTOutput descriptor updated: view={}", rtOutputImageView);
            }

            // ── Step 6: 建立帶 export 標誌的 VkSemaphore（GPU→GL 同步）───────────
            if (canExport) {
                VkExportSemaphoreCreateInfo exportSem = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);

                VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(exportSem.address());

                LongBuffer pSem = stack.mallocLong(1);
                result = vkCreateSemaphore(device, semInfo, null, pSem);
                if (result == VK_SUCCESS) {
                    doneSemaphore = pSem.get(0);
                } else {
                    LOGGER.warn("[Phase6] vkCreateSemaphore (exportable) failed: {} — GPU sync will use glFlush fallback", result);
                }
            }

            // ── Step 7: 建立 Staging Buffer（CPU readback fallback）───────────────
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(pixelBytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuf = stack.mallocLong(1);
            result = vkCreateBuffer(device, bufInfo, null, pBuf);
            if (result == VK_SUCCESS) {
                stagingBuffer = pBuf.get(0);

                VkMemoryRequirements stagingReqs = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, stagingBuffer, stagingReqs);

                int stagingMemType = BRVulkanDevice.findMemoryType(
                    stagingReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                );

                VkMemoryAllocateInfo stagingAlloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(stagingReqs.size())
                    .memoryTypeIndex(stagingMemType);

                LongBuffer pStagingMem = stack.mallocLong(1);
                result = vkAllocateMemory(device, stagingAlloc, null, pStagingMem);
                if (result == VK_SUCCESS) {
                    stagingBufferMemory = pStagingMem.get(0);
                    vkBindBufferMemory(device, stagingBuffer, stagingBufferMemory, 0L);
                } else {
                    LOGGER.warn("[Phase6] staging buffer memory allocation failed: {} — CPU readback unavailable", result);
                    vkDestroyBuffer(device, stagingBuffer, null);
                    stagingBuffer = 0;
                }
            } else {
                LOGGER.warn("[Phase6] staging buffer creation failed: {} — CPU readback unavailable", result);
            }

            LOGGER.info("[Phase6] RT output image ready: {}×{}, image={}, memory={}, view={}, " +
                "semaphore={}, staging={}, exportable={}",
                width, height, rtOutputImage, rtOutputImageMemory,
                rtOutputImageView, doneSemaphore, stagingBuffer, canExport);

            // RT-5-2: GBuffer 附件隨 RT 輸出解析度一同初始化
            BRGBufferAttachments.getInstance().init(width, height);
            LOGGER.debug("[Phase6] GBuffer attachments initialised alongside RT output ({}×{})", width, height);

        } catch (Exception e) {
            LOGGER.error("[Phase6] initOutputImage failed", e);
            cleanupOutputImage();
        }
    }

    /**
     * Image layout 轉換（single-use command buffer）。
     */
    private static void transitionImageLayout(VkDevice device, long image,
            int oldLayout, int newLayout,
            int srcAccessMask, int dstAccessMask,
            int srcStageMask, int dstStageMask) {

        long cmd = BRVulkanDevice.allocateCommandBuffer();
        if (cmd == VK_NULL_HANDLE) {
            LOGGER.warn("[Phase6] transitionImageLayout: cannot allocate command buffer");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer cmdBuf = new VkCommandBuffer(cmd, device);
            vkBeginCommandBuffer(cmdBuf, beginInfo);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(srcAccessMask)
                .dstAccessMask(dstAccessMask);

            vkCmdPipelineBarrier(cmdBuf,
                srcStageMask, dstStageMask,
                0, null, null, barrier);

            vkEndCommandBuffer(cmdBuf);

            // 提交並等待
            VkQueue queue = BRVulkanDevice.getVkQueueObj();
            if (queue != null) {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmdBuf));
                vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
                vkQueueWaitIdle(queue);
            }

            // 釋放 command buffer
            LongBuffer pPool = stack.longs(BRVulkanDevice.getCommandPoolHandle());
            vkFreeCommandBuffers(device, BRVulkanDevice.getCommandPoolHandle(), cmdBuf);

        } catch (Exception e) {
            LOGGER.warn("[Phase6] transitionImageLayout error: {}", e.getMessage());
        }
    }

    /**
     * 清除 RT 輸出 image 所有資源（VkImage、VkDeviceMemory、VkImageView、VkSemaphore、staging buffer）。
     * 由 {@link #cleanup()} 呼叫。
     */
    private static void cleanupOutputImage() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;
        try {
            if (rtOutputImageView != 0) {
                vkDestroyImageView(device, rtOutputImageView, null);
                rtOutputImageView = 0;
            }
            if (doneSemaphore != 0) {
                vkDestroySemaphore(device, doneSemaphore, null);
                doneSemaphore = 0;
            }
            if (rtOutputImage != 0) {
                vkDestroyImage(device, rtOutputImage, null);
                rtOutputImage = 0;
            }
            if (rtOutputImageMemory != 0) {
                vkFreeMemory(device, rtOutputImageMemory, null);
                rtOutputImageMemory = 0;
            }
            if (stagingBuffer != 0) {
                vkDestroyBuffer(device, stagingBuffer, null);
                stagingBuffer = 0;
            }
            if (stagingBufferMemory != 0) {
                vkFreeMemory(device, stagingBufferMemory, null);
                stagingBufferMemory = 0;
            }
            readbackBuffer = null;
            rtOutputWidth  = 0;
            rtOutputHeight = 0;
        } catch (Exception e) {
            LOGGER.warn("[Phase6] cleanupOutputImage error: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Phase 6: GL/VK fd 匯出
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 匯出 RT 輸出 VkImage 的記憶體為 POSIX opaque fd。
     *
     * <p>回傳的 fd 所有權移交呼叫端（GL）；Vulkan 端不得再使用。
     * 僅在 {@link #initOutputImage} 成功且硬體支援 VK_KHR_external_memory_fd 時有效。
     *
     * @return fd ≥ 0，或 -1 表示不可用
     */
    public static int exportOutputMemoryFd() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null || rtOutputImageMemory == 0 || !BRVulkanDevice.hasExternalMemory()) {
            return -1;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryGetFdInfoKHR fdInfo = VkMemoryGetFdInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                .memory(rtOutputImageMemory)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);

            IntBuffer pFd = stack.mallocInt(1);
            int result = vkGetMemoryFdKHR(device, fdInfo, pFd);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[Phase6] vkGetMemoryFdKHR failed: {}", result);
                return -1;
            }
            int fd = pFd.get(0);
            LOGGER.debug("[Phase6] RT output memory fd={} exported", fd);
            return fd;
        } catch (Exception e) {
            LOGGER.warn("[Phase6] exportOutputMemoryFd error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 匯出 RT 完成 VkSemaphore 為 POSIX opaque fd。
     *
     * <p>GL 端使用 {@code GL_EXT_semaphore_fd} 匯入此 fd 做 GPU 同步，
     * 確保 VK RT 寫入完成後 GL 才讀取共享紋理（取代 glFinish）。
     *
     * @return fd ≥ 0，或 -1 表示不可用
     */
    public static int exportDoneSemaphoreFd() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null || doneSemaphore == 0) return -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreGetFdInfoKHR semFdInfo = VkSemaphoreGetFdInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                .semaphore(doneSemaphore)
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);

            IntBuffer pFd = stack.mallocInt(1);
            int result = vkGetSemaphoreFdKHR(device, semFdInfo, pFd);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[Phase6] vkGetSemaphoreFdKHR failed: {}", result);
                return -1;
            }
            return pFd.get(0);
        } catch (Exception e) {
            LOGGER.warn("[Phase6] exportDoneSemaphoreFd error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * CPU Readback：回傳上一幀 RT 輸出的像素數據（RGBA16F）。
     * 由 {@link #traceRays(int, int)} 在每幀更新後可讀。
     *
     * @return RGBA16F 像素數據，或 null 表示尚無數據
     */
    public static ByteBuffer getReadbackBuffer() {
        return readbackBuffer;
    }

    // ── Effect toggles ──────────────────────────────────────────────────────

    public static void enableEffect(RTEffect effect) {
        enabledEffects.add(effect);
        LOGGER.debug("RT effect enabled: {} (est. {:.1f}ms)", effect.name(), effect.estimatedCostMs);
    }

    public static void disableEffect(RTEffect effect) {
        enabledEffects.remove(effect);
        LOGGER.debug("RT effect disabled: {}", effect.name());
    }

    public static boolean isEffectEnabled(RTEffect effect) {
        return enabledEffects.contains(effect);
    }

    public static EnumSet<RTEffect> getEnabledEffects() {
        return EnumSet.copyOf(enabledEffects);
    }

    // ── Trace dispatch ──────────────────────────────────────────────────────

    /**
     * Dispatch ray tracing for the current frame.
     *
     * <ol>
     *   <li>Record command buffer</li>
     *   <li>Bind RT pipeline</li>
     *   <li>Update descriptor set (TLAS from BRVulkanBVH, output from BRVulkanInterop)</li>
     *   <li>{@code vkCmdTraceRaysKHR} with SBT regions</li>
     *   <li>Submit command buffer</li>
     * </ol>
     *
     * @param width  dispatch width in pixels
     * @param height dispatch height in pixels
     */
    public static void traceRays(int width, int height) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            long commandBuffer = BRVulkanDevice.beginSingleTimeCommands(device);

            // Bind ray tracing pipeline
            BRVulkanDevice.cmdBindPipeline(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */, rtPipeline);

            // Bind descriptor sets
            BRVulkanDevice.cmdBindDescriptorSets(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */,
                    rtPipelineLayout, 0, rtDescriptorSet);

            // Get SBT device address
            long sbtAddress = BRVulkanDevice.getBufferDeviceAddress(device, sbtBuffer);

            // Trace rays
            long startTime = System.nanoTime();
            BRVulkanDevice.cmdTraceRaysKHR(commandBuffer,
                    sbtAddress + raygenRegionOffset, raygenRegionStride, raygenRegionSize,
                    sbtAddress + missRegionOffset, missRegionStride, missRegionSize,
                    sbtAddress + hitRegionOffset, hitRegionStride, hitRegionSize,
                    0, 0, 0, // callable (unused)
                    width, height, 1);

            BRVulkanDevice.endSingleTimeCommands(device, commandBuffer);

            // ── Phase 6D: CPU Readback (RT output image → staging buffer → host) ──────
            // Runs only when the staging buffer was successfully created by initOutputImage().
            // BRVKGLSync.uploadFallbackFrame() reads readbackBuffer each frame.
            if (stagingBuffer != 0L && rtOutputImage != 0L
                    && rtOutputWidth > 0 && rtOutputHeight > 0) {
                VkDevice vkDev   = BRVulkanDevice.getVkDeviceObj();
                VkQueue  vkQueue = BRVulkanDevice.getVkQueueObj();
                long     cmdPool = BRVulkanDevice.getCommandPoolHandle();
                if (vkDev != null && vkQueue != null && cmdPool != 0L) {
                    try (MemoryStack s = MemoryStack.stackPush()) {
                        // Allocate one-time copy command buffer
                        var pCb = s.mallocPointer(1);
                        int cbResult = vkAllocateCommandBuffers(vkDev,
                            VkCommandBufferAllocateInfo.calloc(s)
                                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                                .commandPool(cmdPool)
                                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                .commandBufferCount(1),
                            pCb);
                        if (cbResult == VK_SUCCESS) {
                            VkCommandBuffer cb = new VkCommandBuffer(pCb.get(0), vkDev);
                            vkBeginCommandBuffer(cb,
                                VkCommandBufferBeginInfo.calloc(s)
                                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

                            // Barrier: IMAGE_LAYOUT_GENERAL → TRANSFER_SRC_OPTIMAL
                            VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, s)
                                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                                .image(rtOutputImage);
                            toSrc.get(0).subresourceRange()
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1)
                                .baseArrayLayer(0).layerCount(1);
                            vkCmdPipelineBarrier(cb,
                                0x00200000 /* VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR */,
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, null, null, toSrc);

                            // Copy rtOutputImage → stagingBuffer
                            VkBufferImageCopy.Buffer copyRgn = VkBufferImageCopy.calloc(1, s)
                                .bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                            copyRgn.get(0).imageSubresource()
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1);
                            copyRgn.get(0).imageOffset().set(0, 0, 0);
                            copyRgn.get(0).imageExtent().set(rtOutputWidth, rtOutputHeight, 1);
                            vkCmdCopyImageToBuffer(cb,
                                rtOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                stagingBuffer, copyRgn);

                            // Barrier: TRANSFER_SRC_OPTIMAL → GENERAL (restore for next frame)
                            toSrc.get(0)
                                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .newLayout(VK_IMAGE_LAYOUT_GENERAL);
                            vkCmdPipelineBarrier(cb,
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0x00200000 /* VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR */,
                                0, null, null, toSrc);

                            vkEndCommandBuffer(cb);

                            // Submit and stall (per-frame readback — acceptable on Fallback path)
                            vkQueueSubmit(vkQueue,
                                VkSubmitInfo.calloc(s)
                                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                                    .pCommandBuffers(s.pointers(cb)),
                                VK_NULL_HANDLE);
                            vkQueueWaitIdle(vkQueue);
                            vkFreeCommandBuffers(vkDev, cmdPool, cb);

                            // Map staging memory and snapshot into readbackBuffer
                            long stagingSize = (long) rtOutputWidth * rtOutputHeight * 8L; // RGBA16F
                            var pData = s.mallocPointer(1);
                            if (vkMapMemory(vkDev, stagingBufferMemory, 0L, stagingSize, 0, pData)
                                    == VK_SUCCESS) {
                                ByteBuffer mapped = MemoryUtil.memByteBuffer(
                                    pData.get(0), (int) stagingSize);
                                if (readbackBuffer == null
                                        || readbackBuffer.capacity() != (int) stagingSize) {
                                    if (readbackBuffer != null) MemoryUtil.memFree(readbackBuffer);
                                    readbackBuffer = MemoryUtil.memAlloc((int) stagingSize);
                                }
                                readbackBuffer.clear().put(mapped).flip();
                                vkUnmapMemory(vkDev, stagingBufferMemory);
                            }
                        } else {
                            LOGGER.warn("[Phase6D] vkAllocateCommandBuffers failed ({}); skipping readback",
                                cbResult);
                        }
                    } catch (Exception rbEx) {
                        LOGGER.warn("[Phase6D] CPU readback failed: {}", rbEx.getMessage());
                    }
                }
            }

            long elapsed = System.nanoTime() - startTime;
            lastTraceTimeMs = elapsed / 1_000_000.0f;
            totalRaysTraced += (long) width * height;
            frameCount++;
        } catch (Exception e) {
            LOGGER.error("Error during ray trace dispatch", e);
        }
    }

    /**
     * Update the descriptor set bindings for TLAS and output image.
     *
     * @param tlas            Vulkan acceleration structure handle
     * @param outputImageView VkImageView for the RT output
     */
    public static void updateDescriptors(long tlas, long outputImageView) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateRTDescriptorSet(device, rtDescriptorSet, tlas, outputImageView);
        } catch (Exception e) {
            LOGGER.error("Failed to update RT descriptors", e);
        }
    }

    /**
     * Upload camera data to the UBO bound at binding 4.
     */
    public static void setCameraData(Matrix4f invViewProj,
                                     float camX, float camY, float camZ,
                                     float sunDirX, float sunDirY, float sunDirZ) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateCameraUBO(device, rtDescriptorSet,
                    invViewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ);
        } catch (Exception e) {
            LOGGER.error("Failed to update camera UBO", e);
        }
    }

    // ── Temporal reprojection ────────────────────────────────────────────────

    /**
     * 上傳前一幀的 invViewProj 到 CameraUBO offset 64（第二個 mat4）。
     *
     * <p>必須在 {@link #setCameraData} <b>之前</b>呼叫，確保 SVGF denoiser 的
     * temporal reprojection 使用正確的前幀矩陣：
     * <pre>
     * BRVulkanRT.setPrevInvViewProj(prevFrame);   // 寫 offset 64
     * BRVulkanRT.setCameraData(curInvVP, ...);     // 寫 offset 0
     * prevFrame.set(curInvVP);                     // 更新 VkRTPipeline 的快取
     * </pre>
     *
     * <p>GLSL motion vector 計算：
     * <pre>
     * vec4 prevClip = cam.prevInvViewProj * vec4(worldPos, 1.0);
     * vec2 prevUV   = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
     * vec2 motionVec = uv - prevUV;
     * </pre>
     *
     * @param prevInvViewProj 前一幀的 inverse view-projection 矩陣
     */
    public static void setPrevInvViewProj(Matrix4f prevInvViewProj) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updatePrevInvViewProjUBO(device, rtDescriptorSet, prevInvViewProj);
        } catch (Exception e) {
            LOGGER.debug("Failed to update prevInvViewProj UBO: {}", e.getMessage());
        }
    }

    // ── Weather + frame index injection ─────────────────────────────────────

    /**
     * Upload weather state to the CameraUBO's weatherData field.
     *
     * <p>Called by {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline} each frame
     * before {@link #traceRays(int, int)}.
     *
     * @param wetness      global surface wetness factor [0=dry, 1=fully wet]
     * @param snowCoverage global snow coverage [0=none, 1=full]
     */
    public static void setWeatherUniforms(float wetness, float snowCoverage) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateWeatherUBO(device, rtDescriptorSet, wetness, snowCoverage);
        } catch (Exception e) {
            LOGGER.debug("Failed to update weather UBO: {}", e.getMessage());
        }
    }

    /**
     * Upload current frame index for Halton sequence / temporal accumulation.
     *
     * @param frameIndex monotonically increasing frame counter
     */
    public static void updateFrameIndex(long frameIndex) {
        if (!initialized) return;
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateFrameIndexUBO(device, rtDescriptorSet, frameIndex);
        } catch (Exception e) {
            LOGGER.debug("Failed to update frameIndex UBO: {}", e.getMessage());
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────

    public static float getLastTraceTimeMs() {
        return lastTraceTimeMs;
    }

    public static long getTotalRaysTraced() {
        return totalRaysTraced;
    }

    // ── Phase 8: 三路徑 Pass 調度接口 ─────────────────────────────────────────
    //
    // 由 BRRTPipelineOrdering 調用。每個方法對應一個 RT 渲染 Pass。
    // Phase 8 整合：方法存根已建立，GPU 命令緩衝錄製在後續 PR 中補充。
    // 各方法均為 no-throw — 內部異常以 WARN 記錄，不向上傳遞（管線健壯性）。

    /**
     * Phase 8 GBuffer Pass — 填充位置/法線/反射率/材料/深度 GBuffer 附件。
     * <p>於 Blackwell 與 Ada 路徑的首個 Pass 呼叫。
     */
    public static void renderGBuffer(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：錄製 GBuffer 繪製命令，繫結 GBuffer FBO
            // 暫時委託現有 shadow pass 維持正確管線狀態
            LOGGER.trace("[Phase8] GBuffer pass — ctx.fbo={}", ctx.getFramebufferId());
        } catch (Exception e) {
            LOGGER.warn("[Phase8] renderGBuffer failed", e);
        }
    }

    /**
     * Phase 8 ReSTIR DI Dispatch（Blackwell 路徑）。
     * <p>對直接光照進行 Resampled Importance Sampling。
     */
    public static void dispatchReSTIRDI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Ping-pong reservoir buffers before compute dispatch
            BRReSTIRDI.getInstance().swap();
            // Lazy-init compute dispatcher (shares rtOutputWidth × rtOutputHeight with RT image)
            BRReSTIRComputeDispatcher dispatcher = BRReSTIRComputeDispatcher.getInstance();
            if (!dispatcher.isInitialized()) {
                dispatcher.init(rtOutputWidth, rtOutputHeight);
            }
            dispatcher.dispatchDI();
            LOGGER.trace("[Phase8] ReSTIR DI — compute pipeline dispatched ({}×{})", rtOutputWidth, rtOutputHeight);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReSTIRDI failed", e);
        }
    }

    /**
     * Phase 8 ReSTIR GI Dispatch（Blackwell 路徑）。
     * <p>對間接光照進行 Resampled Importance Sampling（多 GI ray）。
     */
    public static void dispatchReSTIRGI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Ping-pong reservoir buffers before compute dispatch
            BRReSTIRGI.getInstance().swap();
            // Reuse the same dispatcher instance initialised by dispatchReSTIRDI
            BRReSTIRComputeDispatcher dispatcher = BRReSTIRComputeDispatcher.getInstance();
            if (!dispatcher.isInitialized()) {
                dispatcher.init(rtOutputWidth, rtOutputHeight);
            }
            dispatcher.dispatchGI();
            LOGGER.trace("[Phase8] ReSTIR GI — compute pipeline dispatched");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReSTIRGI failed", e);
        }
    }

    /**
     * Phase 8 Shadow + AO 合併 Pass（Ada 路徑）。
     * <p>使用 Ray Query Compute Shader + SER 優化 warp 效率。
     */
    public static void dispatchShadowAndAO(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Lazy-init a dedicated VkRTAO pipeline for the Ada Ray Query + SER path.
            // This is separate from the BRRTCompositor-owned instance to keep dispatch
            // lifecycle aligned with BRVulkanRT (Vulkan-side) rather than the GL compositor.
            if (shadowAoPipeline == null) {
                shadowAoPipeline = new com.blockreality.api.client.rendering.vulkan.VkRTAO();
                shadowAoPipeline.init(rtOutputWidth, rtOutputHeight);
            }
            long tlas = BRVulkanBVH.getTLAS();
            if (tlas == 0L) {
                LOGGER.trace("[Phase8] Shadow+AO skipped — TLAS not ready");
                return;
            }
            // Compute inverse view-projection for world-position reconstruction in the shader
            Matrix4f invVP = new Matrix4f(ctx.getProjectionMatrix())
                    .mul(ctx.getViewMatrix())
                    .invert(new Matrix4f());
            // depthTex / normalTex are GL IDs (currently unused by VkRTAO — Vulkan-side
            // GBuffer is bound through the descriptor set built in VkRTAO.init())
            shadowAoPipeline.dispatchAO(0, 0, invVP, tlas, frameCount);
            LOGGER.trace("[Phase8] Shadow+AO dispatched — Ada Ray Query path, frame={}", frameCount);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchShadowAndAO failed", e);
        }
    }

    /**
     * Phase 8 DDGI Sample Pass（Ada 路徑）。
     * <p>幾何表面採樣 Irradiance Volume，輸出 GI diffuse 至 NRD 輸入 buffer。
     */
    public static void dispatchDDGISample(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            BRDDGIProbeSystem ddgi = BRDDGIProbeSystem.getInstance();
            if (!ddgi.isInitialized()) {
                // First-time init: real Vulkan images via BRVulkanDevice.createImage2D() (RT-6-1)
                if (!ddgi.init(BRRTSettings.getInstance().getDdgiProbeSpacingBlocks())) {
                    LOGGER.warn("[Phase8] DDGI init failed — skipping probe update");
                    return;
                }
            }
            // Camera position drives probe grid origin + activation budget
            net.minecraft.world.phys.Vec3 mc = ctx.getCamera().getPosition();
            org.joml.Vector3f camPos = new org.joml.Vector3f((float) mc.x, (float) mc.y, (float) mc.z);
            // 20% rolling update: each frame updates ~3 276 of 16 384 probes (5-frame full cycle)
            int[] updateProbes = ddgi.onFrameStart(camPos, 0.20f);
            if (updateProbes.length > 0) {
                // Lazy-init DDGI compute dispatcher (ddgi_update.comp.glsl pipeline)
                BRDDGIComputeDispatcher ddgiDispatch = BRDDGIComputeDispatcher.getInstance();
                if (!ddgiDispatch.isInitialized()) {
                    ddgiDispatch.init();
                }
                if (ddgiDispatch.isInitialized()) {
                    ddgiDispatch.dispatch(updateProbes, frameCount);
                }
            }
            LOGGER.trace("[Phase8] DDGI probe update — {} probes dispatched (frame={})",
                    updateProbes.length, frameCount);
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDDGISample failed", e);
        }
    }

    /**
     * Phase 8 NRD Dispatch（Blackwell: ReBLUR；Ada: ReLAX + SIGMA）。
     * <p>前置條件：{@link BRNRDNative#isNrdAvailable()} == true。
     */
    public static void dispatchNRD() {
        if (!initialized) return;
        try {
            BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
            if (BRNRDNative.isNrdAvailable()) {
                // Lazy-create NRD denoiser instance (ReBLUR on Blackwell, ReLAX on Ada)
                if (nrdDenoiserHandle == 0L && rtOutputWidth > 0 && rtOutputHeight > 0) {
                    nrdDenoiserHandle = BRNRDNative.createDenoiser(rtOutputWidth, rtOutputHeight, 8);
                    LOGGER.info("[Phase8] NRD SDK denoiser created ({}×{})", rtOutputWidth, rtOutputHeight);
                }
                if (nrdDenoiserHandle != 0L) {
                    // Pass VkImage handle addresses to the native denoiser.
                    // inMotion = 0L until motion-vector export is wired (RT-6-2).
                    BRNRDNative.denoise(nrdDenoiserHandle,
                            rtOutputImageView,       // inColor  — RT output RGBA16F (GENERAL)
                            gbuf.getNormalView(),    // inNormal — world-space normal RGBA16F
                            0L,                      // inMotion — motion vectors (future RT-6-2)
                            gbuf.getDepthView(),     // inDepth  — linear depth R32F (GENERAL)
                            rtOutputImageView);      // outColor — in-place denoise
                    LOGGER.trace("[Phase8] NRD dispatch — SDK path (handle={})", nrdDenoiserHandle);
                }
            } else {
                // NRD SDK not loaded — delegate to pure-Vulkan ReLAX fallback
                if (BRReLAXDenoiser.isInitialized()) {
                    BRReLAXDenoiser.denoise(rtOutputImageView,
                            gbuf.getDepthView(), gbuf.getPrevDepthView(), gbuf.getNormalView());
                }
                LOGGER.trace("[Phase8] NRD dispatch — ReLAX fallback");
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchNRD failed", e);
        }
    }

    /**
     * Phase 8 ReLAX Fallback Denoiser（P2-A：NRD SDK 不可用時的主後備降噪器）。
     *
     * <p>採用純 Vulkan compute 實作，與 RT 管線共享命令緩衝區基礎設施，
     * 相較 {@link #dispatchSVGFFallback} 消除了 GL ↔ Vulkan 跨 API 同步開銷。</p>
     *
     * <p>當前整合點（RT-5-2 完整實作後補充 VkImageView 繫結）：
     * <ul>
     *   <li>currentRTView — RT 輸出 STORAGE_IMAGE（rgba16f，GENERAL）</li>
     *   <li>depthView / prevDepthView — G-Buffer 深度（SHADER_READ_ONLY）</li>
     *   <li>normalView — G-Buffer 法線（SHADER_READ_ONLY）</li>
     * </ul>
     */
    public static void dispatchReLAXFallback(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            if (BRReLAXDenoiser.isInitialized()) {
                // RT-5-2: 使用 BRGBufferAttachments 提供的真實 VkImageView handle
                BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
                long currentRTView  = rtOutputImageView;           // RT 輸出（RGBA16F，GENERAL）
                long depthView      = gbuf.getDepthView();         // 當前幀線性深度（R32F，GENERAL）
                long prevDepthView  = gbuf.getPrevDepthView();     // 前一幀線性深度（R32F，GENERAL）
                long normalView     = gbuf.getNormalView();        // 世界空間法線（RGBA16F，SHADER_READ_ONLY）
                BRReLAXDenoiser.denoise(currentRTView, depthView, prevDepthView, normalView);
                LOGGER.trace("[Phase8] ReLAX fallback denoiser dispatch — rt={} depth={} prevDepth={} normal={}",
                    currentRTView, depthView, prevDepthView, normalView);
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchReLAXFallback failed", e);
        }
    }

    /**
     * Phase 8 SVGF Fallback Denoiser（@Deprecated 最後後備，GL compute）。
     * @deprecated 使用 {@link #dispatchReLAXFallback} — 純 Vulkan 實作，效能更優
     */
    @Deprecated(since = "P2-A", forRemoval = true)
    public static void dispatchSVGFFallback(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // BRSVGFDenoiser uses static methods; full texture handle binding wired in RT-5-2
            if (BRSVGFDenoiser.isInitialized()) {
                LOGGER.trace("[Phase8] SVGF fallback denoiser dispatch");
            }
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchSVGFFallback failed", e);
        }
    }

    /**
     * Phase 8 DLSS Multi-Frame Generation Dispatch（Blackwell 路徑，×3 幀生成）。
     */
    public static void dispatchDLSSMultiFrameGen() {
        if (!initialized) return;
        try {
            // Phase 8 整合點：呼叫 BRDLSS4Manager 的 MFG evaluate
            LOGGER.trace("[Phase8] DLSS MFG dispatch — Blackwell path");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDLSSMultiFrameGen failed", e);
        }
    }

    /**
     * Phase 8 DLSS Frame Generation Dispatch（Ada 路徑，×1 幀生成）。
     */
    public static void dispatchDLSSFrameGen() {
        if (!initialized) return;
        try {
            // Phase 8 整合點：呼叫 BRDLSS4Manager 的 FG evaluate
            LOGGER.trace("[Phase8] DLSS FG dispatch — Ada path");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchDLSSFrameGen failed", e);
        }
    }

    /**
     * Phase 8 Tone Mapping Pass（HDR → LDR ACES filmic）。
     */
    public static void dispatchTonemap(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：全螢幕 quad + tonemap fragment shader
            LOGGER.trace("[Phase8] Tonemap dispatch");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchTonemap failed", e);
        }
    }

    /**
     * Phase 8 UI 覆蓋層 Pass（HUD / 工具提示 / 除錯資訊）。
     */
    public static void dispatchUI(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // Phase 8 整合點：提交 Minecraft UI command buffer
            LOGGER.trace("[Phase8] UI dispatch");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] dispatchUI failed", e);
        }
    }

    /**
     * Legacy 路徑完整幀調度 — 沿用既有非 Phase 8 管線。
     * <p>由 {@link com.blockreality.api.client.render.pipeline.BRRTPipelineOrdering}
     * 在 TIER_LEGACY_RT 路徑下調用。
     */
    public static void renderFrameLegacy(com.blockreality.api.client.render.pipeline.RenderPassContext ctx) {
        if (!initialized) return;
        try {
            // 委託既有 traceRays + SVGF 管線
            // BVH handle managed by BRVulkanBVH statics — no local reference needed
            int width  = 1920;  // Phase 8: 從 ctx 或 BRVulkanDevice 取得實際解析度
            int height = 1080;
            traceRays(width, height);
            LOGGER.trace("[Phase8] Legacy frame dispatch complete");
        } catch (Exception e) {
            LOGGER.warn("[Phase8] renderFrameLegacy failed", e);
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private static long createDescriptorSetLayout(long device) {
        // Binding 0: acceleration structure (TLAS)
        // Binding 1: storage image (RT output, rgba16f)
        // Binding 2: combined image sampler (gbuffer depth)
        // Binding 3: combined image sampler (gbuffer normal)
        // Binding 4: uniform buffer (camera UBO)
        return BRVulkanDevice.createRTDescriptorSetLayout(device);
    }

    private static long createPipelineLayout(long device, long descriptorSetLayout) {
        return BRVulkanDevice.createPipelineLayout(device, descriptorSetLayout);
    }

    private static long createShaderModule(long device, String glslSource, String name) {
        // Compile GLSL to SPIR-V via shaderc (runtime) or load pre-compiled resource
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glslSource, name);
        if (spirv == null || spirv.length == 0) {
            throw new RuntimeException("Failed to compile RT shader: " + name);
        }
        return BRVulkanDevice.createShaderModule(device, spirv);
    }

    /**
     * 帶 anyhit 的 RT pipeline（主路徑）：
     * hitgroup 描述中同時包含 closesthit + anyhit，SBT 仍為 3 條目。
     *
     * @param device         Vulkan 邏輯裝置 handle
     * @param pipelineLayout pipeline layout handle（含 descriptor set layout）
     * @param raygenModule   raygen shader module handle
     * @param missModule     miss shader module handle
     * @param chitModule     closest-hit shader module handle
     * @param ahitModule     any-hit shader module handle
     * @return 建立完成的 RT pipeline handle
     */
    private static long createRTPipelineWithAnyhit(long device, long pipelineLayout,
            long raygenModule, long missModule, long chitModule, long ahitModule) {
        return BRVulkanDevice.createRTPipelineWithAnyhit(
                device, pipelineLayout, raygenModule, missModule, chitModule, ahitModule, 2);
    }

    /**
     * Copies shader group handles from the RT pipeline into the SBT buffer.
     * Each handle is copied at aligned stride intervals.
     */
    private static void copyShaderGroupHandlesToSBT(long device, long pipeline,
            long sbtMemory, long sbtSize, int handleSize, int alignedHandleSize, int groupCount) {
        byte[] handles = BRVulkanDevice.getRayTracingShaderGroupHandles(
                device, pipeline, groupCount, handleSize);
        if (handles.length == 0) {
            LOGGER.error("[RT-SBT] Failed to get shader group handles");
            return;
        }
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            int r = vkMapMemory(vkDev, sbtMemory, 0, sbtSize, 0, pData);
            if (r != VK_SUCCESS) {
                LOGGER.error("[RT-SBT] vkMapMemory failed: {}", r);
                return;
            }
            long addr = pData.get(0);
            for (int i = 0; i < groupCount; i++) {
                MemoryUtil.memCopy(
                    MemoryUtil.memAddress(java.nio.ByteBuffer.wrap(handles, i * handleSize, handleSize)),
                    addr + (long) i * alignedHandleSize,
                    handleSize);
            }
            vkUnmapMemory(vkDev, sbtMemory);
        } catch (Exception e) {
            LOGGER.error("[RT-SBT] copyShaderGroupHandlesToSBT failed", e);
        }
    }

    /**
     * Creates the RT descriptor pool with the required descriptor types.
     */
    private static long createDescriptorPool(long device) {
        return BRVulkanDevice.createRTDescriptorPool(device);
    }

    /**
     * Allocates a single descriptor set from the given pool and layout.
     */
    private static long allocateDescriptorSet(long device, long pool, long layout) {
        return BRVulkanDevice.allocateDescriptorSet(device, pool, layout);
    }
}
>>>>>>> 13cb758 (fix: 修復物理引擎測試 — FES結果快取、SVO壓縮計數、DDGI南極點映射)
