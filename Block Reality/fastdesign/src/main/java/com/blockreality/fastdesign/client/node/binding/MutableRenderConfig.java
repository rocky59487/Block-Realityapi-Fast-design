package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * BRRenderConfig 可變鏡像 — 設計報告 §12.1 N3-1
 *
 * BRRenderConfig 使用 static final（JIT 內聯），無法在 runtime 修改。
 * 此類鏡像所有欄位為 volatile mutable，供節點系統覆蓋。
 *
 * LivePreviewBridge 在每幀渲染前注入這些覆蓋值。
 * 不修改 api 模組 — 保持 fastdesign → api 單向依賴。
 */
@OnlyIn(Dist.CLIENT)
public class MutableRenderConfig {

    private static final MutableRenderConfig INSTANCE = new MutableRenderConfig();
    public static MutableRenderConfig getInstance() { return INSTANCE; }

    private boolean overrideActive = false;

    // ═══════════════════════════════════════════════════════════════════
    //  Pipeline
    // ═══════════════════════════════════════════════════════════════════
    public volatile int shadowMapResolution = BRRenderConfig.SHADOW_MAP_RESOLUTION;
    public volatile float shadowMaxDistance = BRRenderConfig.SHADOW_MAX_DISTANCE;
    public volatile int gbufferAttachmentCount = BRRenderConfig.GBUFFER_ATTACHMENT_COUNT;
    public volatile boolean hdrEnabled = BRRenderConfig.HDR_ENABLED;
    public volatile boolean ssaoEnabled = BRRenderConfig.SSAO_ENABLED;
    public volatile int ssaoKernelSize = BRRenderConfig.SSAO_KERNEL_SIZE;
    public volatile float ssaoRadius = BRRenderConfig.SSAO_RADIUS;
    public volatile boolean gtaoEnabled = BRRenderConfig.GTAO_ENABLED;
    public volatile int gtaoSlices = BRRenderConfig.GTAO_SLICES;
    public volatile int gtaoStepsPerSlice = BRRenderConfig.GTAO_STEPS_PER_SLICE;
    public volatile float gtaoRadius = BRRenderConfig.GTAO_RADIUS;
    public volatile float gtaoFalloffExponent = BRRenderConfig.GTAO_FALLOFF_EXPONENT;

    // ═══════════════════════════════════════════════════════════════════
    //  Optimization
    // ═══════════════════════════════════════════════════════════════════
    public volatile int greedyMeshMaxArea = BRRenderConfig.GREEDY_MESH_MAX_AREA;
    public volatile int meshCacheMaxSections = BRRenderConfig.MESH_CACHE_MAX_SECTIONS;
    public volatile float frustumPadding = BRRenderConfig.FRUSTUM_PADDING;
    public volatile int batchMaxVertices = BRRenderConfig.BATCH_MAX_VERTICES;

    // ═══════════════════════════════════════════════════════════════════
    //  LOD
    // ═══════════════════════════════════════════════════════════════════
    public volatile double lodMaxDistance = BRRenderConfig.LOD_MAX_DISTANCE;
    public volatile int lodLevelCount = BRRenderConfig.LOD_LEVEL_COUNT;
    public volatile double lodHysteresis = BRRenderConfig.LOD_HYSTERESIS;
    public volatile int lodVramBudgetMb = BRRenderConfig.LOD_VRAM_BUDGET_MB;

    // ═══════════════════════════════════════════════════════════════════
    //  Ray Tracing（Phase 4-3）
    // ═══════════════════════════════════════════════════════════════════
    /** 每次投影的陰影光線數（1–8，越多越柔和但越慢） */
    public volatile int rtShadowRays = 1;
    /** 反射遞歸深度（0 = 停用 RT 反射，1–4 = 鏡面反射層數） */
    public volatile int rtReflectionBounces = 1;
    /** 是否啟用 RT 全域光照（GI） */
    public volatile boolean rtGIEnabled = false;
    /** RT GI 每像素取樣光線數（1–4） */
    public volatile int rtGIRays = 1;
    /** RT 降噪強度（0.0 = 關閉，1.0 = 最強） */
    public volatile float rtDenoiserStrength = 0.5f;

    // ═══════════════════════════════════════════════════════════════════
    //  Shader / PBR
    // ═══════════════════════════════════════════════════════════════════
    public volatile float pbrDefaultMetallic = BRRenderConfig.PBR_DEFAULT_METALLIC;
    public volatile float pbrDefaultRoughness = BRRenderConfig.PBR_DEFAULT_ROUGHNESS;
    public volatile float aoStrength = BRRenderConfig.AO_STRENGTH;
    public volatile float bloomThreshold = BRRenderConfig.BLOOM_THRESHOLD;
    public volatile float bloomIntensity = BRRenderConfig.BLOOM_INTENSITY;
    public volatile int tonemapMode = BRRenderConfig.TONEMAP_MODE;

    // ═══════════════════════════════════════════════════════════════════
    //  Auto Exposure
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean autoExposureEnabled = BRRenderConfig.AUTO_EXPOSURE_ENABLED;
    public volatile float autoExposureAdaptSpeed = BRRenderConfig.AUTO_EXPOSURE_ADAPT_SPEED;
    public volatile float autoExposureMinEv = BRRenderConfig.AUTO_EXPOSURE_MIN_EV;
    public volatile float autoExposureMaxEv = BRRenderConfig.AUTO_EXPOSURE_MAX_EV;

    // ═══════════════════════════════════════════════════════════════════
    //  TAA
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean taaEnabled = BRRenderConfig.TAA_ENABLED;
    public volatile float taaBlendFactor = BRRenderConfig.TAA_BLEND_FACTOR;
    public volatile int taaJitterSamples = BRRenderConfig.TAA_JITTER_SAMPLES;

    // ═══════════════════════════════════════════════════════════════════
    //  SSR
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean ssrEnabled = BRRenderConfig.SSR_ENABLED;
    public volatile float ssrMaxDistance = BRRenderConfig.SSR_MAX_DISTANCE;
    public volatile int ssrMaxSteps = BRRenderConfig.SSR_MAX_STEPS;
    public volatile int ssrBinarySteps = BRRenderConfig.SSR_BINARY_STEPS;
    public volatile float ssrThickness = BRRenderConfig.SSR_THICKNESS;
    public volatile float ssrFadeEdge = BRRenderConfig.SSR_FADE_EDGE;

    // ═══════════════════════════════════════════════════════════════════
    //  Volumetric
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean volumetricEnabled = BRRenderConfig.VOLUMETRIC_ENABLED;
    public volatile int volumetricRaySteps = BRRenderConfig.VOLUMETRIC_RAY_STEPS;
    public volatile float volumetricFogDensity = BRRenderConfig.VOLUMETRIC_FOG_DENSITY;
    public volatile float volumetricScatterStrength = BRRenderConfig.VOLUMETRIC_SCATTER_STRENGTH;

    // ═══════════════════════════════════════════════════════════════════
    //  DoF
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean dofEnabled = BRRenderConfig.DOF_ENABLED;
    public volatile float dofFocusDist = BRRenderConfig.DOF_FOCUS_DIST;
    public volatile float dofAperture = BRRenderConfig.DOF_APERTURE;
    public volatile int dofSampleCount = BRRenderConfig.DOF_SAMPLE_COUNT;

    // ═══════════════════════════════════════════════════════════════════
    //  Contact Shadow
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean contactShadowEnabled = BRRenderConfig.CONTACT_SHADOW_ENABLED;
    public volatile float contactShadowMaxDist = BRRenderConfig.CONTACT_SHADOW_MAX_DIST;
    public volatile int contactShadowSteps = BRRenderConfig.CONTACT_SHADOW_STEPS;

    // ═══════════════════════════════════════════════════════════════════
    //  Atmosphere
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean atmosphereEnabled = BRRenderConfig.ATMOSPHERE_ENABLED;
    public volatile float atmosphereSunIntensity = BRRenderConfig.ATMOSPHERE_SUN_INTENSITY;

    // ═══════════════════════════════════════════════════════════════════
    //  Water
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean waterEnabled = BRRenderConfig.WATER_ENABLED;
    public volatile float waterReflectionScale = BRRenderConfig.WATER_REFLECTION_SCALE;
    public volatile int waterWaveCount = BRRenderConfig.WATER_WAVE_COUNT;
    public volatile float waterFoamThreshold = BRRenderConfig.WATER_FOAM_THRESHOLD;
    public volatile float waterCausticsIntensity = BRRenderConfig.WATER_CAUSTICS_INTENSITY;

    // ═══════════════════════════════════════════════════════════════════
    //  Particles
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean particlesEnabled = BRRenderConfig.PARTICLES_ENABLED;
    public volatile int particleMaxCount = BRRenderConfig.PARTICLE_MAX_COUNT;

    // ═══════════════════════════════════════════════════════════════════
    //  CSM
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean csmEnabled = BRRenderConfig.CSM_ENABLED;
    public volatile float csmMaxDistance = BRRenderConfig.CSM_MAX_DISTANCE;
    public volatile int csmCascadeCount = BRRenderConfig.CSM_CASCADE_COUNT;

    // ═══════════════════════════════════════════════════════════════════
    //  Clouds
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean cloudEnabled = BRRenderConfig.CLOUD_ENABLED;
    public volatile float cloudBottomHeight = BRRenderConfig.CLOUD_BOTTOM_HEIGHT;
    public volatile float cloudThickness = BRRenderConfig.CLOUD_THICKNESS;
    public volatile float cloudDefaultCoverage = BRRenderConfig.CLOUD_DEFAULT_COVERAGE;

    // ═══════════════════════════════════════════════════════════════════
    //  Cinematic
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean cinematicEnabled = BRRenderConfig.CINEMATIC_ENABLED;
    public volatile float cinematicVignetteIntensity = BRRenderConfig.CINEMATIC_VIGNETTE_INTENSITY;
    public volatile float cinematicChromaticAberration = BRRenderConfig.CINEMATIC_CHROMATIC_ABERRATION;
    public volatile float cinematicMotionBlur = BRRenderConfig.CINEMATIC_MOTION_BLUR;
    public volatile int cinematicMotionBlurSamples = BRRenderConfig.CINEMATIC_MOTION_BLUR_SAMPLES;
    public volatile float cinematicFilmGrain = BRRenderConfig.CINEMATIC_FILM_GRAIN;

    // ═══════════════════════════════════════════════════════════════════
    //  Color Grading
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean colorGradingEnabled = BRRenderConfig.COLOR_GRADING_ENABLED;
    public volatile float colorGradingIntensity = BRRenderConfig.COLOR_GRADING_INTENSITY;
    public volatile float colorGradingTemperature = BRRenderConfig.COLOR_GRADING_TEMPERATURE;
    public volatile float colorGradingSaturation = BRRenderConfig.COLOR_GRADING_SATURATION;
    public volatile float colorGradingContrast = BRRenderConfig.COLOR_GRADING_CONTRAST;

    // ═══════════════════════════════════════════════════════════════════
    //  SSGI
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean ssgiEnabled = BRRenderConfig.SSGI_ENABLED;
    public volatile float ssgiIntensity = BRRenderConfig.SSGI_INTENSITY;
    public volatile float ssgiRadius = BRRenderConfig.SSGI_RADIUS;
    public volatile int ssgiSamples = BRRenderConfig.SSGI_SAMPLES;

    // ═══════════════════════════════════════════════════════════════════
    //  Fog
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean fogEnabled = BRRenderConfig.FOG_ENABLED;
    public volatile float fogDistanceDensity = BRRenderConfig.FOG_DISTANCE_DENSITY;
    public volatile float fogHeightDensity = BRRenderConfig.FOG_HEIGHT_DENSITY;
    public volatile float fogHeightFalloff = BRRenderConfig.FOG_HEIGHT_FALLOFF;

    // ═══════════════════════════════════════════════════════════════════
    //  Lens Flare
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean lensFlareEnabled = BRRenderConfig.LENS_FLARE_ENABLED;
    public volatile float lensFlareIntensity = BRRenderConfig.LENS_FLARE_INTENSITY;

    // ═══════════════════════════════════════════════════════════════════
    //  Weather
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean weatherEnabled = BRRenderConfig.WEATHER_ENABLED;
    public volatile int rainDropsPerTick = BRRenderConfig.RAIN_DROPS_PER_TICK;
    public volatile int snowFlakesPerTick = BRRenderConfig.SNOW_FLAKES_PER_TICK;
    public volatile float auroraHeight = BRRenderConfig.AURORA_HEIGHT;

    // ═══════════════════════════════════════════════════════════════════
    //  SSS
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean sssEnabled = BRRenderConfig.SSS_ENABLED;
    public volatile float sssWidth = BRRenderConfig.SSS_WIDTH;
    public volatile float sssStrength = BRRenderConfig.SSS_STRENGTH;

    // ═══════════════════════════════════════════════════════════════════
    //  Anisotropic / POM / Misc
    // ═══════════════════════════════════════════════════════════════════
    public volatile boolean anisotropicEnabled = BRRenderConfig.ANISOTROPIC_ENABLED;
    public volatile float anisotropicStrength = BRRenderConfig.ANISOTROPIC_STRENGTH;
    public volatile boolean pomEnabled = BRRenderConfig.POM_ENABLED;
    public volatile float pomScale = BRRenderConfig.POM_SCALE;
    public volatile int pomSteps = BRRenderConfig.POM_STEPS;
    public volatile boolean shaderLodEnabled = BRRenderConfig.SHADER_LOD_ENABLED;
    public volatile boolean occlusionQueryEnabled = BRRenderConfig.OCCLUSION_QUERY_ENABLED;
    public volatile boolean gpuProfilerEnabled = BRRenderConfig.GPU_PROFILER_ENABLED;

    // ═══════════════════════════════════════════════════════════════════
    //  Ghost Block / Selection
    // ═══════════════════════════════════════════════════════════════════
    public volatile float ghostBlockAlpha = BRRenderConfig.GHOST_BLOCK_ALPHA;
    public volatile float ghostBreatheAmp = BRRenderConfig.GHOST_BREATHE_AMP;
    public volatile float ghostScanSpeed = BRRenderConfig.GHOST_SCAN_SPEED;
    public volatile float selectionVizPulseSpeed = BRRenderConfig.SELECTION_VIZ_PULSE_SPEED;
    public volatile float selectionVizFillAlpha = BRRenderConfig.SELECTION_VIZ_FILL_ALPHA;

    // ─── 控制 ───

    public boolean isOverrideActive() { return overrideActive; }
    public void setOverrideActive(boolean active) { this.overrideActive = active; }

    /**
     * 重置所有欄位為 BRRenderConfig 的原始 static final 值。
     */
    public void resetToDefaults() {
        shadowMapResolution = BRRenderConfig.SHADOW_MAP_RESOLUTION;
        shadowMaxDistance = BRRenderConfig.SHADOW_MAX_DISTANCE;
        ssaoEnabled = BRRenderConfig.SSAO_ENABLED;
        ssaoRadius = BRRenderConfig.SSAO_RADIUS;
        gtaoEnabled = BRRenderConfig.GTAO_ENABLED;
        taaEnabled = BRRenderConfig.TAA_ENABLED;
        ssrEnabled = BRRenderConfig.SSR_ENABLED;
        volumetricEnabled = BRRenderConfig.VOLUMETRIC_ENABLED;
        bloomThreshold = BRRenderConfig.BLOOM_THRESHOLD;
        bloomIntensity = BRRenderConfig.BLOOM_INTENSITY;
        lodMaxDistance = BRRenderConfig.LOD_MAX_DISTANCE;
        rtShadowRays = 1;
        rtReflectionBounces = 1;
        rtGIEnabled = false;
        rtGIRays = 1;
        rtDenoiserStrength = 0.5f;
        cloudEnabled = BRRenderConfig.CLOUD_ENABLED;
        weatherEnabled = BRRenderConfig.WEATHER_ENABLED;
        fogEnabled = BRRenderConfig.FOG_ENABLED;
        ssgiEnabled = BRRenderConfig.SSGI_ENABLED;
        colorGradingEnabled = BRRenderConfig.COLOR_GRADING_ENABLED;
        overrideActive = false;
    }
}
