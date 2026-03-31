package com.blockreality.api.client.render.shader;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 固化光影引擎 — 所有 Shader 在啟動時一次性編譯，不可修改。
 *
 * 融合技術：
 *   - Iris: 多 pass shader 架構（shadow / gbuffer / deferred / composite / final）
 *   - Radiance: PBR 材質模型 + ACES 色調映射
 *   - Sodium/Embeddium: 頂點格式優化（壓縮 normal、material ID 打包）
 *
 * Shader 清單（固化，不可外部新增）：
 *   1. shadow        — 深度寫入（vertex-only 最小化）
 *   2. gbuffer_terrain — 結構方塊 GBuffer 填充（PBR 材質）
 *   3. gbuffer_entity  — 實體/骨骼動畫 GBuffer
 *   4. translucent    — 半透明幾何（選框、幽靈方塊）
 *   5. deferred       — 延遲光照計算
 *   6. ssao           — 環境光遮蔽
 *   7. bloom          — 泛光提取 + 高斯模糊
 *   8. tonemap        — ACES 色調映射 + gamma
 *   9. final_blit     — 最終輸出
 *  10. overlay        — UI 覆蓋層（前向渲染）
 *  11. selection_glow — 選框發光特效
 *  12. placement_fx   — 放置動畫粒子
 */
@OnlyIn(Dist.CLIENT)
public final class BRShaderEngine {
    private BRShaderEngine() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Shader");

    // ─── Shader Programs ────────────────────────────────
    private static BRShaderProgram shadowShader;
    private static BRShaderProgram gbufferTerrainShader;
    private static BRShaderProgram gbufferEntityShader;
    private static BRShaderProgram translucentShader;
    private static BRShaderProgram deferredLightingShader;
    private static BRShaderProgram ssaoShader;
    private static BRShaderProgram bloomShader;
    private static BRShaderProgram tonemapShader;
    private static BRShaderProgram finalShader;
    private static BRShaderProgram overlayShader;
    private static BRShaderProgram selectionGlowShader;
    private static BRShaderProgram placementFxShader;
    private static BRShaderProgram lodTerrainShader;
    private static BRShaderProgram taaShader;
    private static BRShaderProgram selectionVizShader;
    private static BRShaderProgram ghostBlockShader;
    private static BRShaderProgram volumetricShader;
    private static BRShaderProgram ssrShader;
    private static BRShaderProgram dofShader;
    private static BRShaderProgram contactShadowShader;
    private static BRShaderProgram atmosphereShader;
    private static BRShaderProgram waterShader;
    private static BRShaderProgram particleShader;
    private static BRShaderProgram csmShader;
    private static BRShaderProgram cloudShader;
    private static BRShaderProgram cinematicShader;
    private static BRShaderProgram velocityShader;
    private static BRShaderProgram colorGradeShader;
    private static BRShaderProgram debugShader;
    private static BRShaderProgram ssgiShader;
    private static BRShaderProgram fogShader;
    private static BRShaderProgram lensFlareShader;
    // Phase 10: Weather
    private static BRShaderProgram rainShader;
    private static BRShaderProgram snowShader;
    private static BRShaderProgram lightningShader;
    private static BRShaderProgram auroraShader;
    private static BRShaderProgram wetPbrShader;
    // Phase 11: Material Enhancement
    private static BRShaderProgram sssShader;
    private static BRShaderProgram anisotropicShader;
    private static BRShaderProgram pomShader;
    // Occlusion Query
    private static BRShaderProgram occlusionQueryShader;
    // Phase 12: Advanced Rendering — Report v4 未實現功能
    private static BRShaderProgram vctCompositeShader;
    private static BRShaderProgram meshletGbufferShader;
    private static BRShaderProgram hiZDownsampleShader;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 — 一次性編譯所有固化 shader
    // ═══════════════════════════════════════════════════════

    // ★ 編譯統計（供外部查詢診斷）
    private static int compiledCount = 0;
    private static int failedCount = 0;
    private static String lastFailedShader = "";

    /** 已成功編譯的 shader 數量 */
    public static int getCompiledCount() { return compiledCount; }
    /** 編譯失敗的 shader 數量 */
    public static int getFailedCount() { return failedCount; }
    /** 最後一個失敗的 shader 名稱（空字串表示全部成功） */
    public static String getLastFailedShader() { return lastFailedShader; }

    /**
     * 安全編譯單一 shader — 失敗時返回 null 而非拋出異常。
     * 這樣單一 shader 的編譯錯誤不會導致全部 43 個 shader 被銷毀。
     */
    private static BRShaderProgram safeCompile(String name, String vert, String frag) {
        try {
            BRShaderProgram prog = new BRShaderProgram(name, vert, frag);
            compiledCount++;
            return prog;
        } catch (Exception e) {
            failedCount++;
            lastFailedShader = name;
            LOG.error("Shader 編譯失敗 '{}': {}", name, e.getMessage());
            return null;
        }
    }

    public static void init() {
        if (initialized) return;
        LOG.info("開始編譯固化光影...");
        long t0 = System.nanoTime();
        compiledCount = 0;
        failedCount = 0;
        lastFailedShader = "";

        // ★ v4 修復：逐一編譯每個 shader，失敗的個別跳過，不連帶銷毀其他已編譯的 shader。
        //   原先使用單一 try-catch 包裹所有 43 個 shader，任何一個失敗就全部清除。
        shadowShader          = safeCompile("br_shadow",       SHADOW_VERT, SHADOW_FRAG);
        gbufferTerrainShader  = safeCompile("br_gbuffer_terrain", GBUFFER_TERRAIN_VERT, GBUFFER_TERRAIN_FRAG);
        gbufferEntityShader   = safeCompile("br_gbuffer_entity",  GBUFFER_ENTITY_VERT, GBUFFER_ENTITY_FRAG);
        translucentShader     = safeCompile("br_translucent",   TRANSLUCENT_VERT, TRANSLUCENT_FRAG);
        deferredLightingShader = safeCompile("br_deferred",     FULLSCREEN_VERT, DEFERRED_FRAG);
        ssaoShader            = safeCompile("br_ssao",          FULLSCREEN_VERT, SSAO_FRAG);
        bloomShader           = safeCompile("br_bloom",         FULLSCREEN_VERT, BLOOM_FRAG);
        tonemapShader         = safeCompile("br_tonemap",       FULLSCREEN_VERT, TONEMAP_FRAG);
        finalShader           = safeCompile("br_final",         FULLSCREEN_VERT, FINAL_FRAG);
        overlayShader         = safeCompile("br_overlay",       OVERLAY_VERT, OVERLAY_FRAG);
        selectionGlowShader   = safeCompile("br_selection_glow", SELECTION_GLOW_VERT, SELECTION_GLOW_FRAG);
        placementFxShader     = safeCompile("br_placement_fx",  PLACEMENT_FX_VERT, PLACEMENT_FX_FRAG);
        lodTerrainShader      = safeCompile("br_lod_terrain",   LOD_TERRAIN_VERT, LOD_TERRAIN_FRAG);
        taaShader             = safeCompile("br_taa",            FULLSCREEN_VERT, TAA_FRAG);
        selectionVizShader    = safeCompile("br_selection_viz",  SELECTION_VIZ_VERT, SELECTION_VIZ_FRAG);
        ghostBlockShader      = safeCompile("br_ghost_block",   GHOST_BLOCK_VERT, GHOST_BLOCK_FRAG);
        volumetricShader      = safeCompile("br_volumetric",    FULLSCREEN_VERT, VOLUMETRIC_FRAG);
        ssrShader             = safeCompile("br_ssr",            FULLSCREEN_VERT, SSR_FRAG);
        dofShader             = safeCompile("br_dof",            FULLSCREEN_VERT, DOF_FRAG);
        contactShadowShader   = safeCompile("br_contact_shadow", FULLSCREEN_VERT, CONTACT_SHADOW_FRAG);
        atmosphereShader      = safeCompile("br_atmosphere",     FULLSCREEN_VERT, ATMOSPHERE_FRAG);
        waterShader           = safeCompile("br_water",          WATER_VERT, WATER_FRAG);
        particleShader        = safeCompile("br_particle",       PARTICLE_VERT, PARTICLE_FRAG);
        csmShader             = safeCompile("br_csm",             FULLSCREEN_VERT, CSM_FRAG);
        cloudShader           = safeCompile("br_cloud",           FULLSCREEN_VERT, CLOUD_FRAG);
        cinematicShader       = safeCompile("br_cinematic",       FULLSCREEN_VERT, CINEMATIC_FRAG);
        velocityShader        = safeCompile("br_velocity",        FULLSCREEN_VERT, VELOCITY_FRAG);
        colorGradeShader      = safeCompile("br_color_grade",     FULLSCREEN_VERT, COLOR_GRADE_FRAG);
        debugShader           = safeCompile("br_debug",           FULLSCREEN_VERT, DEBUG_FRAG);
        ssgiShader            = safeCompile("br_ssgi",            FULLSCREEN_VERT, SSGI_FRAG);
        fogShader             = safeCompile("br_fog",             FULLSCREEN_VERT, FOG_FRAG);
        lensFlareShader       = safeCompile("br_lens_flare",      FULLSCREEN_VERT, LENS_FLARE_FRAG);
        // Phase 10: Weather
        rainShader            = safeCompile("br_rain",             RAIN_VERT, RAIN_FRAG);
        snowShader            = safeCompile("br_snow",             SNOW_VERT, SNOW_FRAG);
        lightningShader       = safeCompile("br_lightning",         FULLSCREEN_VERT, LIGHTNING_FRAG);
        auroraShader          = safeCompile("br_aurora",            FULLSCREEN_VERT, AURORA_FRAG);
        wetPbrShader          = safeCompile("br_wet_pbr",           FULLSCREEN_VERT, WET_PBR_FRAG);
        // Phase 11: Material Enhancement
        sssShader             = safeCompile("br_sss",               FULLSCREEN_VERT, SSS_FRAG);
        anisotropicShader     = safeCompile("br_anisotropic",       FULLSCREEN_VERT, ANISOTROPIC_FRAG);
        pomShader             = safeCompile("br_pom",               FULLSCREEN_VERT, POM_FRAG);
        // Occlusion Query: 最小化 AABB 代理幾何 shader
        occlusionQueryShader  = safeCompile("br_occlusion_query",    OCCLUSION_QUERY_VERT, OCCLUSION_QUERY_FRAG);
        // Phase 12: Advanced Rendering
        vctCompositeShader    = safeCompile("br_vct_composite",       FULLSCREEN_VERT, VCT_COMPOSITE_FRAG);
        meshletGbufferShader  = safeCompile("br_meshlet_gbuffer",     GBUFFER_TERRAIN_VERT, GBUFFER_TERRAIN_FRAG);
        hiZDownsampleShader   = safeCompile("br_hiz_downsample",      FULLSCREEN_VERT, HIZ_DOWNSAMPLE_FRAG);

        // ★ 只要有至少一個核心 shader（finalShader）成功就標記為已初始化
        //   這樣即使部分 shader 失敗，能用的 pass 仍然會執行
        initialized = (finalShader != null);

        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        if (failedCount == 0) {
            LOG.info("固化光影編譯完成 — {} 個 shader, {}ms", compiledCount, elapsed);
        } else {
            LOG.warn("固化光影編譯部分完成 — 成功 {}, 失敗 {}, {}ms (最後失敗: {})",
                compiledCount, failedCount, elapsed, lastFailedShader);
        }

        // ★ 關鍵 shader 缺失時警告（finalShader 是必須的，否則後處理結果無法寫回螢幕）
        if (finalShader == null) {
            LOG.error("★ 關鍵 shader 'br_final' 編譯失敗 — 後處理管線將無法輸出！");
        }
        if (bloomShader == null) {
            LOG.warn("★ Bloom shader 編譯失敗 — 泛光效果將不可用");
        }
        if (tonemapShader == null) {
            LOG.warn("★ Tonemap shader 編譯失敗 — 色調映射將不可用");
        }
    }

    // ─── Accessors ──────────────────────────────────────

    public static BRShaderProgram getShadowShader()          { return shadowShader; }
    public static BRShaderProgram getGBufferTerrainShader()   { return gbufferTerrainShader; }
    public static BRShaderProgram getGBufferEntityShader()    { return gbufferEntityShader; }
    public static BRShaderProgram getTranslucentShader()      { return translucentShader; }
    public static BRShaderProgram getDeferredLightingShader() { return deferredLightingShader; }
    public static BRShaderProgram getSSAOShader()             { return ssaoShader; }
    public static BRShaderProgram getBloomShader()            { return bloomShader; }
    public static BRShaderProgram getTonemapShader()          { return tonemapShader; }
    public static BRShaderProgram getFinalShader()            { return finalShader; }
    public static BRShaderProgram getOverlayShader()          { return overlayShader; }
    public static BRShaderProgram getSelectionGlowShader()    { return selectionGlowShader; }
    public static BRShaderProgram getPlacementFxShader()      { return placementFxShader; }
    public static BRShaderProgram getLODTerrainShader()       { return lodTerrainShader; }
    public static BRShaderProgram getTAAShader()              { return taaShader; }
    public static BRShaderProgram getSelectionVizShader()    { return selectionVizShader; }
    public static BRShaderProgram getGhostBlockShader()      { return ghostBlockShader; }
    public static BRShaderProgram getVolumetricShader()      { return volumetricShader; }
    public static BRShaderProgram getSSRShader()             { return ssrShader; }
    public static BRShaderProgram getDOFShader()             { return dofShader; }
    public static BRShaderProgram getContactShadowShader()   { return contactShadowShader; }
    public static BRShaderProgram getAtmosphereShader()      { return atmosphereShader; }
    public static BRShaderProgram getWaterShader()           { return waterShader; }
    public static BRShaderProgram getParticleShader()        { return particleShader; }
    public static BRShaderProgram getCSMShader()             { return csmShader; }
    public static BRShaderProgram getCloudShader()           { return cloudShader; }
    public static BRShaderProgram getCinematicShader()       { return cinematicShader; }
    public static BRShaderProgram getVelocityShader()        { return velocityShader; }
    public static BRShaderProgram getColorGradeShader()      { return colorGradeShader; }
    public static BRShaderProgram getDebugShader()           { return debugShader; }
    public static BRShaderProgram getSSGIShader()            { return ssgiShader; }
    public static BRShaderProgram getFogShader()             { return fogShader; }
    public static BRShaderProgram getLensFlareShader()       { return lensFlareShader; }
    // Phase 10: Weather
    public static BRShaderProgram getRainShader()            { return rainShader; }
    public static BRShaderProgram getSnowShader()            { return snowShader; }
    public static BRShaderProgram getLightningShader()        { return lightningShader; }
    public static BRShaderProgram getAuroraShader()           { return auroraShader; }
    public static BRShaderProgram getWetPbrShader()           { return wetPbrShader; }
    // Phase 11: Material Enhancement
    public static BRShaderProgram getSSSShader()              { return sssShader; }
    public static BRShaderProgram getAnisotropicShader()     { return anisotropicShader; }
    public static BRShaderProgram getPOMShader()              { return pomShader; }
    // Occlusion Query
    public static BRShaderProgram getOcclusionQueryShader()  { return occlusionQueryShader; }
    // Phase 12: Advanced Rendering
    public static BRShaderProgram getVCTCompositeShader()    { return vctCompositeShader; }
    public static BRShaderProgram getMeshletGbufferShader()  { return meshletGbufferShader; }
    public static BRShaderProgram getHiZDownsampleShader()   { return hiZDownsampleShader; }
    /** LOD 地形著色器（別名 lodTerrainShader） */
    public static BRShaderProgram getLODShader()             { return lodTerrainShader; }

    public static boolean isInitialized() { return initialized; }

    // ─── Cleanup ────────────────────────────────────────

    public static void cleanup() {
        cleanupPartial();
        initialized = false;
    }

    private static void cleanupPartial() {
        if (shadowShader != null)           { shadowShader.delete(); shadowShader = null; }
        if (gbufferTerrainShader != null)    { gbufferTerrainShader.delete(); gbufferTerrainShader = null; }
        if (gbufferEntityShader != null)     { gbufferEntityShader.delete(); gbufferEntityShader = null; }
        if (translucentShader != null)       { translucentShader.delete(); translucentShader = null; }
        if (deferredLightingShader != null)  { deferredLightingShader.delete(); deferredLightingShader = null; }
        if (ssaoShader != null)             { ssaoShader.delete(); ssaoShader = null; }
        if (bloomShader != null)            { bloomShader.delete(); bloomShader = null; }
        if (tonemapShader != null)          { tonemapShader.delete(); tonemapShader = null; }
        if (finalShader != null)            { finalShader.delete(); finalShader = null; }
        if (overlayShader != null)          { overlayShader.delete(); overlayShader = null; }
        if (selectionGlowShader != null)    { selectionGlowShader.delete(); selectionGlowShader = null; }
        if (placementFxShader != null)      { placementFxShader.delete(); placementFxShader = null; }
        if (lodTerrainShader != null)       { lodTerrainShader.delete(); lodTerrainShader = null; }
        if (taaShader != null)             { taaShader.delete(); taaShader = null; }
        if (selectionVizShader != null)   { selectionVizShader.delete(); selectionVizShader = null; }
        if (ghostBlockShader != null)     { ghostBlockShader.delete(); ghostBlockShader = null; }
        if (volumetricShader != null)     { volumetricShader.delete(); volumetricShader = null; }
        if (ssrShader != null)           { ssrShader.delete(); ssrShader = null; }
        if (dofShader != null)           { dofShader.delete(); dofShader = null; }
        if (contactShadowShader != null) { contactShadowShader.delete(); contactShadowShader = null; }
        if (atmosphereShader != null)    { atmosphereShader.delete(); atmosphereShader = null; }
        if (waterShader != null)         { waterShader.delete(); waterShader = null; }
        if (particleShader != null)      { particleShader.delete(); particleShader = null; }
        if (csmShader != null)           { csmShader.delete(); csmShader = null; }
        if (cloudShader != null)         { cloudShader.delete(); cloudShader = null; }
        if (cinematicShader != null)     { cinematicShader.delete(); cinematicShader = null; }
        if (velocityShader != null)      { velocityShader.delete(); velocityShader = null; }
        if (colorGradeShader != null)    { colorGradeShader.delete(); colorGradeShader = null; }
        if (debugShader != null)         { debugShader.delete(); debugShader = null; }
        if (ssgiShader != null)          { ssgiShader.delete(); ssgiShader = null; }
        if (fogShader != null)           { fogShader.delete(); fogShader = null; }
        if (lensFlareShader != null)     { lensFlareShader.delete(); lensFlareShader = null; }
        // Phase 10: Weather
        if (rainShader != null)          { rainShader.delete(); rainShader = null; }
        if (snowShader != null)          { snowShader.delete(); snowShader = null; }
        if (lightningShader != null)     { lightningShader.delete(); lightningShader = null; }
        if (auroraShader != null)        { auroraShader.delete(); auroraShader = null; }
        if (wetPbrShader != null)        { wetPbrShader.delete(); wetPbrShader = null; }
        // Phase 11: Material Enhancement
        if (sssShader != null)           { sssShader.delete(); sssShader = null; }
        if (anisotropicShader != null)   { anisotropicShader.delete(); anisotropicShader = null; }
        if (pomShader != null)           { pomShader.delete(); pomShader = null; }
        // Occlusion Query
        if (occlusionQueryShader != null) { occlusionQueryShader.delete(); occlusionQueryShader = null; }
        // Phase 12: Advanced Rendering
        if (vctCompositeShader != null)   { vctCompositeShader.delete(); vctCompositeShader = null; }
        if (meshletGbufferShader != null) { meshletGbufferShader.delete(); meshletGbufferShader = null; }
        if (hiZDownsampleShader != null)  { hiZDownsampleShader.delete(); hiZDownsampleShader = null; }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  固化 GLSL 原始碼 — 內嵌 Java 字串（Iris 風格 inline shader）
    //  注意：這些 shader 是 GLSL 3.30 core，配合 Minecraft 1.20.1 的 OpenGL 3.2+ 要求
    // ═══════════════════════════════════════════════════════════════════

    // ── 全螢幕 Quad Vertex Shader（所有後處理 pass 共用）──

    private static final String FULLSCREEN_VERT = """
        #version 330 core
        // 無 VBO 全螢幕三角形 — gl_VertexID trick（Radiance 風格）
        out vec2 v_texCoord;
        void main() {
            // 3 個頂點覆蓋整個螢幕的超大三角形
            vec2 pos = vec2(
                float((gl_VertexID & 1) << 2) - 1.0,
                float((gl_VertexID & 2) << 1) - 1.0
            );
            v_texCoord = pos * 0.5 + 0.5;
            gl_Position = vec4(pos, 0.0, 1.0);
        }
        """;

    // ── Shadow Pass ─────────────────────────────────────

    private static final String SHADOW_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        uniform mat4 u_shadowProj;
        uniform mat4 u_shadowView;
        uniform mat4 u_modelMatrix;
        void main() {
            gl_Position = u_shadowProj * u_shadowView * u_modelMatrix * vec4(a_position, 1.0);
        }
        """;

    private static final String SHADOW_FRAG = """
        #version 330 core
        void main() {
            // 深度自動寫入，無需 color output
        }
        """;

    // ── GBuffer Terrain（PBR 材質輸出）──────────────────

    private static final String GBUFFER_TERRAIN_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec4 a_color;     // 頂點色（材質 tint）
        layout(location = 3) in vec2 a_texCoord;
        layout(location = 4) in float a_materialId; // BR 材質 ID（混凝土/鋼/木/鋼筋）

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;

        out vec3 v_worldPos;
        out vec3 v_normal;
        out vec4 v_color;
        out vec2 v_texCoord;
        out float v_materialId;

        void main() {
            v_worldPos = a_position;
            v_normal = normalize(a_normal);
            v_color = a_color;
            v_texCoord = a_texCoord;
            v_materialId = a_materialId;

            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String GBUFFER_TERRAIN_FRAG = """
        #version 330 core
        in vec3 v_worldPos;
        in vec3 v_normal;
        in vec4 v_color;
        in vec2 v_texCoord;
        in float v_materialId;

        // GBuffer 輸出（Iris 風格 MRT）
        layout(location = 0) out vec4 gbuf_position;   // xyz=world pos, w=depth
        layout(location = 1) out vec4 gbuf_normal;     // xyz=normal, w=unused
        layout(location = 2) out vec4 gbuf_albedo;     // rgb=color, a=alpha
        layout(location = 3) out vec4 gbuf_material;   // r=metallic, g=roughness, b=ao, a=materialId
        layout(location = 4) out vec4 gbuf_emission;   // rgb=emission, a=emissive strength

        // PBR 材質查找表 — 依 BR 材質 ID（Radiance PBR 概念）
        // ID: 0=混凝土, 1=鋼材, 2=木材, 3=鋼筋
        vec3 getMaterialPBR(float matId) {
            int id = int(matId + 0.5);
            // (metallic, roughness, ao)
            if (id == 0) return vec3(0.0, 0.85, 0.9);   // 混凝土: 非金屬、粗糙
            if (id == 1) return vec3(0.95, 0.3, 0.95);  // 鋼材: 高金屬、光滑
            if (id == 2) return vec3(0.0, 0.75, 0.8);   // 木材: 非金屬、中等粗糙
            if (id == 3) return vec3(0.85, 0.4, 0.9);   // 鋼筋: 金屬、中等
            return vec3(0.0, 0.5, 1.0);                  // 預設
        }

        void main() {
            gbuf_position = vec4(v_worldPos, gl_FragCoord.z);
            gbuf_normal   = vec4(normalize(v_normal), 0.0);
            gbuf_albedo   = v_color;

            vec3 pbr = getMaterialPBR(v_materialId);
            gbuf_material = vec4(pbr, v_materialId / 255.0);

            gbuf_emission = vec4(0.0); // BR 方塊預設無自發光
        }
        """;

    // ── GBuffer Entity（骨骼動畫 — GeckoLib 風格）───────

    private static final String GBUFFER_ENTITY_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec4 a_color;
        layout(location = 3) in vec2 a_texCoord;
        layout(location = 4) in float a_materialId;
        layout(location = 5) in ivec4 a_boneIds;     // GeckoLib 風格: 4 骨骼影響
        layout(location = 6) in vec4 a_boneWeights;

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;
        uniform float u_partialTick;

        // 骨骼矩陣陣列（GeckoLib 風格）
        #define MAX_BONES 128
        uniform mat4 u_boneMatrices[MAX_BONES];

        out vec3 v_worldPos;
        out vec3 v_normal;
        out vec4 v_color;
        out float v_materialId;

        void main() {
            // 骨骼蒙皮變換（GeckoLib 的 linear blend skinning）
            mat4 skinMatrix = mat4(0.0);
            for (int i = 0; i < 4; i++) {
                int boneId = a_boneIds[i];
                if (boneId >= 0 && boneId < MAX_BONES) {
                    skinMatrix += u_boneMatrices[boneId] * a_boneWeights[i];
                }
            }
            // 權重為零時使用單位矩陣
            float totalWeight = a_boneWeights.x + a_boneWeights.y + a_boneWeights.z + a_boneWeights.w;
            if (totalWeight < 0.001) skinMatrix = mat4(1.0);

            vec4 skinnedPos = skinMatrix * vec4(a_position, 1.0);
            vec3 skinnedNormal = mat3(skinMatrix) * a_normal;

            v_worldPos = skinnedPos.xyz;
            v_normal = normalize(skinnedNormal);
            v_color = a_color;
            v_materialId = a_materialId;

            vec3 viewPos = skinnedPos.xyz - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String GBUFFER_ENTITY_FRAG = GBUFFER_TERRAIN_FRAG; // 共用 GBuffer 輸出格式

    // ── Translucent（幽靈方塊/選框半透明）────────────────

    private static final String TRANSLUCENT_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec4 a_color;

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;
        uniform float u_partialTick;

        out vec4 v_color;
        out vec3 v_worldPos;

        void main() {
            v_color = a_color;
            v_worldPos = a_position;
            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String TRANSLUCENT_FRAG = """
        #version 330 core
        in vec4 v_color;
        in vec3 v_worldPos;

        layout(location = 0) out vec4 gbuf_position;
        layout(location = 1) out vec4 gbuf_normal;
        layout(location = 2) out vec4 gbuf_albedo;
        layout(location = 3) out vec4 gbuf_material;
        layout(location = 4) out vec4 gbuf_emission;

        void main() {
            if (v_color.a < 0.01) discard;
            gbuf_position = vec4(v_worldPos, gl_FragCoord.z);
            gbuf_normal   = vec4(0.0, 1.0, 0.0, 0.0); // 半透明預設向上法線
            gbuf_albedo   = v_color;
            gbuf_material = vec4(0.0, 0.5, 1.0, 0.0);
            gbuf_emission = vec4(0.0);
        }
        """;

    // ── Deferred Lighting（延遲光照 — Iris deferred 概念）──

    private static final String DEFERRED_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_gbuffer0; // position
        uniform sampler2D u_gbuffer1; // normal
        uniform sampler2D u_gbuffer2; // albedo
        uniform sampler2D u_gbuffer3; // material (metallic, roughness, ao)
        uniform sampler2D u_gbuffer4; // emission
        uniform sampler2D u_depthTex;

        // CSM — 4 個 cascade shadow map（取代舊版 sampler2DShadow u_shadowMap）
        uniform sampler2D u_csm[4];
        uniform mat4      u_lightViewProj[4];
        uniform float     u_cascadeSplit[4];
        uniform vec3      u_lightDir;

        // 深度線性化 / 世界重建
        uniform mat4  u_invViewProj;
        uniform float u_nearPlane;
        uniform float u_farPlane;
        uniform float u_shadowIntensity;

        uniform float u_sunAngle;
        uniform float u_ambientStrength;
        uniform float u_gameTime;

        out vec4 fragColor;

        // Cook-Torrance PBR（Radiance 風格）
        const float PI = 3.14159265359;

        float DistributionGGX(vec3 N, vec3 H, float roughness) {
            float a = roughness * roughness;
            float a2 = a * a;
            float NdotH = max(dot(N, H), 0.0);
            float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
            return a2 / (PI * denom * denom + 0.0001);
        }

        float GeometrySchlickGGX(float NdotV, float roughness) {
            float r = (roughness + 1.0);
            float k = (r * r) / 8.0;
            return NdotV / (NdotV * (1.0 - k) + k);
        }

        float GeometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
            float NdotV = max(dot(N, V), 0.0);
            float NdotL = max(dot(N, L), 0.0);
            return GeometrySchlickGGX(NdotV, roughness) * GeometrySchlickGGX(NdotL, roughness);
        }

        vec3 fresnelSchlick(float cosTheta, vec3 F0) {
            return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
        }

        // ── CSM + PCSS 陰影函數 ─────────────────────────────────────────

        float linearizeDepth(float d) {
            return (2.0 * u_nearPlane * u_farPlane)
                 / (u_farPlane + u_nearPlane - (2.0 * d - 1.0) * (u_farPlane - u_nearPlane));
        }

        // Vogel Disc 低差異取樣（固定 golden angle 螺旋）
        vec2 vogelDisk(int sampleIdx, int sampleCount, float phi) {
            float r     = sqrt(float(sampleIdx) + 0.5) / sqrt(float(sampleCount));
            float theta = float(sampleIdx) * 2.399963 + phi; // 2.4 ≈ golden angle (rad)
            return vec2(r * cos(theta), r * sin(theta));
        }

        // Blocker 搜尋 pass（PCSS 第一步）— 回傳平均 blocker 深度，-1 表示無遮擋
        float searchBlockerDepth(sampler2D shadowMap, vec2 shadowUV, float receiverDepth,
                                  float searchRadius, float phi) {
            float blockerSum   = 0.0;
            int   blockerCount = 0;
            for (int i = 0; i < 16; i++) {
                vec2  offset       = vogelDisk(i, 16, phi) * searchRadius;
                float shadowSample = texture(shadowMap, shadowUV + offset).r;
                if (shadowSample < receiverDepth) {
                    blockerSum += shadowSample;
                    blockerCount++;
                }
            }
            return (blockerCount == 0) ? -1.0 : blockerSum / float(blockerCount);
        }

        // PCF 軟陰影（PCSS 第二步）— 可變核心半徑
        float pcfShadow(sampler2D shadowMap, vec2 shadowUV, float receiverDepth,
                         float filterRadius, float phi) {
            float shadow = 0.0;
            for (int i = 0; i < 25; i++) {
                vec2  offset       = vogelDisk(i, 25, phi) * filterRadius;
                float shadowSample = texture(shadowMap, shadowUV + offset).r;
                shadow += (shadowSample < receiverDepth) ? 1.0 : 0.0;
            }
            return shadow / 25.0;
        }

        // 主 CSM 查詢 — 選取正確的 cascade 並做 PCSS
        float computeCSMShadow(vec3 worldPos, float viewDepth) {
            // 選取 cascade（從最近開始）
            int   cascadeIdx = 3;
            for (int i = 0; i < 4; i++) {
                if (viewDepth < u_cascadeSplit[i]) {
                    cascadeIdx = i;
                    break;
                }
            }

            // 投影到光源空間
            vec4 lightSpacePos = u_lightViewProj[cascadeIdx] * vec4(worldPos, 1.0);
            vec3 projCoords    = lightSpacePos.xyz / lightSpacePos.w;
            projCoords         = projCoords * 0.5 + 0.5;

            // 超出 shadow map 範圍 → 全亮
            if (projCoords.x < 0.0 || projCoords.x > 1.0 ||
                projCoords.y < 0.0 || projCoords.y > 1.0 ||
                projCoords.z > 1.0) {
                return 0.0;
            }

            // 自適應 bias（依據 cascade 縮放）
            float bias        = mix(0.0005, 0.004, float(cascadeIdx) / 3.0);
            float receiverZ   = projCoords.z - bias;

            // 雜訊 phi（基於世界座標，防止條帶 artifact）
            float phi = fract(sin(dot(worldPos.xz, vec2(12.9898, 78.233))) * 43758.5453) * 6.28318;

            // PCSS blocker search
            float searchRadius  = 0.005 * (1.0 + float(cascadeIdx) * 0.5);
            float avgBlocker    = searchBlockerDepth(u_csm[cascadeIdx], projCoords.xy,
                                                     receiverZ, searchRadius, phi);
            if (avgBlocker < 0.0) return 0.0; // 無遮擋

            // Penumbra size（半影大小）
            float penumbra      = (receiverZ - avgBlocker) / avgBlocker * 0.02;
            float filterRadius  = clamp(penumbra, 0.0005, 0.01);

            return pcfShadow(u_csm[cascadeIdx], projCoords.xy, receiverZ, filterRadius, phi);
        }

        // ── main ────────────────────────────────────────────────────────

        void main() {
            vec4 posSample = texture(u_gbuffer0, v_texCoord);
            if (posSample.w <= 0.0) discard; // 天空

            vec3 worldPos = posSample.xyz;
            vec3 normal   = normalize(texture(u_gbuffer1, v_texCoord).xyz);
            vec4 albedo   = texture(u_gbuffer2, v_texCoord);
            vec4 matData  = texture(u_gbuffer3, v_texCoord);
            vec3 emission = texture(u_gbuffer4, v_texCoord).rgb;

            float metallic  = matData.r;
            float roughness = matData.g;
            float ao        = matData.b;

            // 太陽光方向（同 Iris sunAngle）
            vec3 lightDir = normalize(vec3(
                cos(u_sunAngle),
                sin(u_sunAngle),
                0.3
            ));
            vec3 lightColor = vec3(1.0, 0.98, 0.95) * 3.0; // HDR 太陽光

            // 簡化視角方向（從 position 到 camera 原點）
            vec3 V = normalize(-worldPos);
            vec3 L = lightDir;
            vec3 H = normalize(V + L);

            // PBR 計算
            vec3 F0 = mix(vec3(0.04), albedo.rgb, metallic);
            float NDF = DistributionGGX(normal, H, roughness);
            float G   = GeometrySmith(normal, V, L, roughness);
            vec3  F   = fresnelSchlick(max(dot(H, V), 0.0), F0);

            vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
            float NdotL = max(dot(normal, L), 0.0);

            vec3 numerator = NDF * G * F;
            float denominator = 4.0 * max(dot(normal, V), 0.0) * NdotL + 0.0001;
            vec3 specular = numerator / denominator;

            vec3 Lo = (kD * albedo.rgb / PI + specular) * lightColor * NdotL;

            // 環境光（簡化 IBL）
            vec3 ambient = vec3(u_ambientStrength) * albedo.rgb * ao;

            // ── CSM 陰影因子（僅影響直接光 Lo，不影響 ambient / emission）
            float rawDepth  = texture(u_depthTex, v_texCoord).r;
            float viewDepth = linearizeDepth(rawDepth);
            float shadow    = computeCSMShadow(worldPos, viewDepth);
            float shadowFactor = 1.0 - shadow * u_shadowIntensity;

            // 最終合成
            vec3 color = ambient + Lo * shadowFactor + emission;

            fragColor = vec4(color, albedo.a);
        }
        """;

    // ── SSAO ────────────────────────────────────────────

    private static final String SSAO_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_normalTex;
        uniform int u_kernelSize;
        uniform float u_radius;
        uniform float u_screenWidth;
        uniform float u_screenHeight;
        uniform float u_gameTime;

        out vec4 fragColor;

        // 簡化 SSAO — hash-based 隨機取樣
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        void main() {
            vec3 color = texture(u_inputTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;

            if (depth >= 1.0) { fragColor = vec4(color, 1.0); return; }

            vec3 normal = texture(u_normalTex, v_texCoord).xyz;
            vec2 texelSize = 1.0 / vec2(u_screenWidth, u_screenHeight);

            float occlusion = 0.0;
            int samples = min(u_kernelSize, 32);

            for (int i = 0; i < samples; i++) {
                float angle = float(i) * 2.399963 + hash(v_texCoord * 1000.0); // golden angle
                float r = u_radius * (float(i + 1) / float(samples));
                vec2 offset = vec2(cos(angle), sin(angle)) * r * texelSize * 20.0;

                float sampleDepth = texture(u_depthTex, v_texCoord + offset).r;
                float rangeCheck = smoothstep(0.0, 1.0, u_radius / abs(depth - sampleDepth + 0.001));
                occlusion += step(sampleDepth, depth - 0.001) * rangeCheck;
            }

            occlusion = 1.0 - (occlusion / float(samples));
            fragColor = vec4(color * occlusion, 1.0);
        }
        """;

    // ── Bloom ───────────────────────────────────────────

    private static final String BLOOM_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform float u_threshold;
        uniform float u_intensity;
        uniform float u_screenWidth;
        uniform float u_screenHeight;
        uniform float u_gameTime;

        out vec4 fragColor;

        void main() {
            vec3 color = texture(u_inputTex, v_texCoord).rgb;
            float brightness = dot(color, vec3(0.2126, 0.7152, 0.0722));

            // 提取亮部
            vec3 bloom = vec3(0.0);
            if (brightness > u_threshold) {
                bloom = color * (brightness - u_threshold);
            }

            // 9-tap 高斯模糊（單 pass 簡化版 — 正式可分離兩 pass）
            vec2 texelSize = 1.0 / vec2(u_screenWidth, u_screenHeight);
            vec3 blurred = vec3(0.0);
            float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

            blurred += bloom * weights[0];
            for (int i = 1; i < 5; i++) {
                blurred += texture(u_inputTex, v_texCoord + vec2(texelSize.x * float(i), 0.0)).rgb * weights[i];
                blurred += texture(u_inputTex, v_texCoord - vec2(texelSize.x * float(i), 0.0)).rgb * weights[i];
                blurred += texture(u_inputTex, v_texCoord + vec2(0.0, texelSize.y * float(i))).rgb * weights[i];
                blurred += texture(u_inputTex, v_texCoord - vec2(0.0, texelSize.y * float(i))).rgb * weights[i];
            }

            fragColor = vec4(color + blurred * u_intensity, 1.0);
        }
        """;

    // ── Tonemap（ACES — Radiance 預設）──────────────────

    private static final String TONEMAP_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform int u_tonemapMode;
        uniform float u_screenWidth;
        uniform float u_screenHeight;
        uniform float u_gameTime;

        out vec4 fragColor;

        // ACES Filmic Tone Mapping（業界標準）
        vec3 acesFilm(vec3 x) {
            float a = 2.51;
            float b = 0.03;
            float c = 2.43;
            float d = 0.59;
            float e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        // Reinhard
        vec3 reinhard(vec3 x) {
            return x / (x + vec3(1.0));
        }

        // Uncharted 2 Filmic
        vec3 uncharted2Helper(vec3 x) {
            float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
            return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
        }
        vec3 uncharted2(vec3 x) {
            float exposureBias = 2.0;
            vec3 curr = uncharted2Helper(x * exposureBias);
            vec3 whiteScale = vec3(1.0) / uncharted2Helper(vec3(11.2));
            return curr * whiteScale;
        }

        void main() {
            vec3 hdr = texture(u_inputTex, v_texCoord).rgb;

            vec3 mapped;
            if (u_tonemapMode == 0) mapped = reinhard(hdr);
            else if (u_tonemapMode == 1) mapped = acesFilm(hdr);
            else mapped = uncharted2(hdr);

            // Gamma 校正
            mapped = pow(mapped, vec3(1.0 / 2.2));

            fragColor = vec4(mapped, 1.0);
        }
        """;

    // ── Final Blit ──────────────────────────────────────

    private static final String FINAL_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        uniform sampler2D u_sceneTex;
        uniform float u_screenWidth;
        uniform float u_screenHeight;
        uniform float u_gameTime;
        out vec4 fragColor;

        // Bayer 4x4 抖動矩陣 — 減少色帶（banding artifact）
        float bayerDither(vec2 pos) {
            int x = int(mod(pos.x, 4.0));
            int y = int(mod(pos.y, 4.0));
            int index = x + y * 4;
            // 歸一化 Bayer matrix 值到 [-0.5, 0.5]
            float bayer[16] = float[16](
                 0.0/16.0,  8.0/16.0,  2.0/16.0, 10.0/16.0,
                12.0/16.0,  4.0/16.0, 14.0/16.0,  6.0/16.0,
                 3.0/16.0, 11.0/16.0,  1.0/16.0,  9.0/16.0,
                15.0/16.0,  7.0/16.0, 13.0/16.0,  5.0/16.0
            );
            return bayer[index] - 0.5;
        }

        // 線性 → sRGB 轉換（精確公式）
        vec3 linearToSRGB(vec3 linear) {
            vec3 low  = linear * 12.92;
            vec3 high = pow(linear, vec3(1.0 / 2.4)) * 1.055 - 0.055;
            return mix(low, high, step(0.0031308, linear));
        }

        void main() {
            vec3 color = texture(u_sceneTex, v_texCoord).rgb;

            // 精確 sRGB gamma 校正（取代 pow(x, 1/2.2) 簡化版本）
            color = linearToSRGB(color);

            // 抖動 — 8-bit 量化前套用，消除 HDR→LDR 色帶
            vec2 fragPos = gl_FragCoord.xy;
            float dither = bayerDither(fragPos) / 255.0;
            color += dither;

            // 鉗制到有效範圍
            color = clamp(color, 0.0, 1.0);

            fragColor = vec4(color, 1.0);
        }
        """;

    // ── Overlay（前向渲染 — UI 元素）────────────────────

    private static final String OVERLAY_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec4 a_color;

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;

        out vec4 v_color;

        void main() {
            v_color = a_color;
            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String OVERLAY_FRAG = """
        #version 330 core
        in vec4 v_color;
        out vec4 fragColor;
        void main() {
            if (v_color.a < 0.01) discard;
            fragColor = v_color;
        }
        """;

    // ── Selection Glow（選框發光特效）───────────────────

    private static final String SELECTION_GLOW_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec4 a_color;

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;
        uniform float u_glowPhase; // 脈衝相位 [0, 2π]

        out vec4 v_color;
        out float v_glow;

        void main() {
            v_color = a_color;
            // 脈衝發光強度
            v_glow = 0.5 + 0.5 * sin(u_glowPhase);

            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String SELECTION_GLOW_FRAG = """
        #version 330 core
        in vec4 v_color;
        in float v_glow;
        out vec4 fragColor;
        void main() {
            // 混合基礎色與發光
            float alpha = mix(0.3, 0.8, v_glow) * v_color.a;
            vec3 glowColor = v_color.rgb + vec3(0.2, 0.3, 0.5) * v_glow;
            fragColor = vec4(glowColor, alpha);
        }
        """;

    // ── Placement FX（放置動畫粒子）─────────────────────

    private static final String PLACEMENT_FX_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;   // 粒子中心
        layout(location = 1) in vec4 a_color;
        layout(location = 2) in float a_size;       // 粒子大小
        layout(location = 3) in float a_life;       // 生命值 [0,1]

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;

        out vec4 v_color;
        out float v_life;

        void main() {
            v_color = a_color;
            v_life = a_life;

            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
            gl_PointSize = a_size * (1.0 - a_life * 0.5); // 隨生命縮小
        }
        """;

    private static final String PLACEMENT_FX_FRAG = """
        #version 330 core
        in vec4 v_color;
        in float v_life;
        out vec4 fragColor;
        void main() {
            // 圓形粒子（距中心衰減）
            vec2 coord = gl_PointCoord * 2.0 - 1.0;
            float dist = dot(coord, coord);
            if (dist > 1.0) discard;

            float alpha = v_color.a * (1.0 - v_life) * (1.0 - dist);
            fragColor = vec4(v_color.rgb, alpha);
        }
        """;

    // ── TAA — Temporal Anti-Aliasing（時序抗鋸齒）─────────
    //  參考論文：Karis 2014 "High Quality Temporal Supersampling"
    //  技術：sub-pixel jitter + 歷史幀重投影 + 鄰域鉗制

    private static final String TAA_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_currentTex;   // 當前幀 HDR color
        uniform sampler2D u_historyTex;   // 上一幀 TAA 結果
        uniform sampler2D u_depthTex;     // 當前幀深度
        uniform sampler2D u_motionTex;    // 運動向量（若無可用深度重投影）
        uniform mat4  u_prevViewProj;     // 上一幀 view-projection 矩陣
        uniform mat4  u_invViewProj;      // 當前幀逆 view-projection
        uniform vec2  u_resolution;       // 螢幕解析度
        uniform vec2  u_jitter;           // sub-pixel 抖動偏移
        uniform float u_blendFactor;      // 歷史混合係數（預設 0.9）
        uniform int   u_frameIndex;       // 幀計數器

        out vec4 fragColor;

        // ─── 深度重投影計算運動向量 ───
        vec2 computeMotionVector(vec2 uv) {
            float depth = texture(u_depthTex, uv).r;
            // NDC 空間重建
            vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 worldPos = u_invViewProj * clipPos;
            worldPos /= worldPos.w;
            // 重投影到上一幀
            vec4 prevClip = u_prevViewProj * worldPos;
            prevClip /= prevClip.w;
            vec2 prevUV = prevClip.xy * 0.5 + 0.5;
            return prevUV - uv;
        }

        // ─── YCoCg 色彩空間轉換（鄰域鉗制更穩定）───
        vec3 RGBToYCoCg(vec3 rgb) {
            return vec3(
                 0.25 * rgb.r + 0.5 * rgb.g + 0.25 * rgb.b,
                 0.5  * rgb.r                - 0.5  * rgb.b,
                -0.25 * rgb.r + 0.5 * rgb.g - 0.25 * rgb.b
            );
        }
        vec3 YCoCgToRGB(vec3 ycocg) {
            return vec3(
                ycocg.x + ycocg.y - ycocg.z,
                ycocg.x           + ycocg.z,
                ycocg.x - ycocg.y - ycocg.z
            );
        }

        // ─── Catmull-Rom 5-tap 歷史採樣（比 bilinear 更銳利）───
        vec3 sampleHistoryCatmullRom(vec2 uv) {
            vec2 texSize = u_resolution;
            vec2 samplePos = uv * texSize;
            vec2 tc = floor(samplePos - 0.5) + 0.5;
            vec2 f = samplePos - tc;
            vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
            vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
            vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
            vec2 w3 = f * f * (-0.5 + 0.5 * f);
            vec2 w12 = w1 + w2;
            vec2 tc0 = (tc - 1.0) / texSize;
            vec2 tc12 = (tc + w2 / w12) / texSize;
            vec2 tc3 = (tc + 2.0) / texSize;
            // 簡化為 5 次採樣
            vec3 result = vec3(0.0);
            result += texture(u_historyTex, vec2(tc12.x, tc12.y)).rgb * (w12.x * w12.y);
            result += texture(u_historyTex, vec2(tc0.x,  tc12.y)).rgb * (w0.x  * w12.y);
            result += texture(u_historyTex, vec2(tc3.x,  tc12.y)).rgb * (w3.x  * w12.y);
            result += texture(u_historyTex, vec2(tc12.x, tc0.y )).rgb * (w12.x * w0.y);
            result += texture(u_historyTex, vec2(tc12.x, tc3.y )).rgb * (w12.x * w3.y);
            float totalWeight = (w12.x * w12.y) + (w0.x * w12.y) + (w3.x * w12.y)
                              + (w12.x * w0.y) + (w12.x * w3.y);
            return result / max(totalWeight, 0.0001);
        }

        void main() {
            // 移除 jitter 偏移取得原始 UV
            vec2 uv = v_texCoord - u_jitter / u_resolution;

            // 當前幀顏色
            vec3 currentColor = texture(u_currentTex, uv).rgb;

            // 運動向量（深度重投影）
            vec2 motion = computeMotionVector(uv);

            // 歷史幀顏色（Catmull-Rom 採樣）
            vec2 historyUV = uv + motion;
            vec3 historyColor;
            if (historyUV.x < 0.0 || historyUV.x > 1.0 ||
                historyUV.y < 0.0 || historyUV.y > 1.0) {
                // 超出螢幕邊界 — 完全使用當前幀
                historyColor = currentColor;
            } else {
                historyColor = sampleHistoryCatmullRom(historyUV);
            }

            // ─── 鄰域鉗制（YCoCg 空間）防止 ghosting ───
            vec3 nearMin = vec3( 99999.0);
            vec3 nearMax = vec3(-99999.0);
            vec2 texelSize = 1.0 / u_resolution;

            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec3 s = RGBToYCoCg(texture(u_currentTex, uv + vec2(x, y) * texelSize).rgb);
                    nearMin = min(nearMin, s);
                    nearMax = max(nearMax, s);
                }
            }

            vec3 histYCoCg = RGBToYCoCg(historyColor);
            histYCoCg = clamp(histYCoCg, nearMin, nearMax);
            historyColor = YCoCgToRGB(histYCoCg);

            // ─── 亮度加權混合 ───
            float lumCurrent = 0.2126 * currentColor.r + 0.7152 * currentColor.g + 0.0722 * currentColor.b;
            float lumHistory = 0.2126 * historyColor.r + 0.7152 * historyColor.g + 0.0722 * historyColor.b;
            float weightCurrent = 1.0 / (1.0 + lumCurrent);
            float weightHistory = 1.0 / (1.0 + lumHistory);

            float blend = u_blendFactor;
            vec3 result = (currentColor * weightCurrent * (1.0 - blend) +
                           historyColor * weightHistory * blend) /
                          (weightCurrent * (1.0 - blend) + weightHistory * blend);

            fragColor = vec4(result, 1.0);
        }
        """;

    // ── LOD Terrain（遠景 LOD 渲染 — DH/Voxy 風格）──────────

    private static final String LOD_TERRAIN_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec4 a_color;
        layout(location = 3) in float a_lodLevel; // LOD 層級（用於淡出混合）

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;
        uniform float u_lodMaxDistance;  // 最大 LOD 渲染距離

        out vec3 v_worldPos;
        out vec3 v_normal;
        out vec4 v_color;
        out float v_lodLevel;
        out float v_distanceFade; // 距離淡出因子

        void main() {
            v_worldPos = a_position;
            v_normal = normalize(a_normal);
            v_color = a_color;
            v_lodLevel = a_lodLevel;

            // 計算到攝影機距離 → 淡出因子（Distant Horizons SMOOTH_DROPOFF 風格）
            float dist = distance(a_position, u_cameraPos);
            float fadeStart = u_lodMaxDistance * 0.85;
            v_distanceFade = 1.0 - smoothstep(fadeStart, u_lodMaxDistance, dist);

            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String LOD_TERRAIN_FRAG = """
        #version 330 core
        in vec3 v_worldPos;
        in vec3 v_normal;
        in vec4 v_color;
        in float v_lodLevel;
        in float v_distanceFade;

        // GBuffer 輸出（同 gbuffer_terrain 格式，共用延遲管線）
        layout(location = 0) out vec4 gbuf_position;
        layout(location = 1) out vec4 gbuf_normal;
        layout(location = 2) out vec4 gbuf_albedo;
        layout(location = 3) out vec4 gbuf_material;
        layout(location = 4) out vec4 gbuf_emission;

        void main() {
            // 距離淡出（遠端透明消失 — DH SMOOTH_DROPOFF）
            if (v_distanceFade < 0.01) discard;

            gbuf_position = vec4(v_worldPos, gl_FragCoord.z);
            gbuf_normal   = vec4(normalize(v_normal), 0.0);

            // LOD 層級顏色微調（越遠越灰 — 模擬大氣散射 fog）
            float fogFactor = v_lodLevel * 0.08;
            vec3 fogColor = vec3(0.7, 0.8, 1.0); // 天空藍霧
            vec3 color = mix(v_color.rgb, fogColor, fogFactor);

            gbuf_albedo   = vec4(color, v_color.a * v_distanceFade);
            gbuf_material = vec4(0.0, 0.85, 0.9, 0.0); // LOD 統一粗糙非金屬材質
            gbuf_emission = vec4(0.0);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  Selection Visualization Shader（Axiom 風格選取視覺化）
    // ══════════════════════════════════════════════════════════════

    private static final String SELECTION_VIZ_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in float a_edgeMask; // 1.0 = 邊緣, 0.0 = 面

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;

        out vec3 v_worldPos;
        out vec3 v_normal;
        out float v_edgeMask;
        out float v_viewDist;

        void main() {
            v_worldPos = a_position;
            v_normal = normalize(a_normal);
            v_edgeMask = a_edgeMask;
            v_viewDist = distance(a_position, u_cameraPos);

            vec3 viewPos = a_position - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String SELECTION_VIZ_FRAG = """
        #version 330 core
        in vec3 v_worldPos;
        in vec3 v_normal;
        in float v_edgeMask;
        in float v_viewDist;

        uniform float u_time;         // 動畫時間
        uniform vec3 u_selColor;      // 選取顏色（預設 0.3, 0.6, 1.0 藍色）
        uniform float u_pulseSpeed;   // 脈衝速度
        uniform float u_fillAlpha;    // 面填充透明度
        uniform float u_edgeAlpha;    // 邊緣透明度
        uniform int u_boolMode;       // 0=Replace, 1=Union, 2=Intersect, 3=Subtract
        uniform float u_maxViewDist;  // 最遠可見距離

        layout(location = 0) out vec4 fragColor;

        void main() {
            // 距離淡出
            float distFade = 1.0 - smoothstep(u_maxViewDist * 0.7, u_maxViewDist, v_viewDist);
            if (distFade < 0.01) discard;

            // 脈衝動畫（sin 波）
            float pulse = 0.5 + 0.5 * sin(u_time * u_pulseSpeed);

            // 根據 boolean 模式調色
            vec3 color = u_selColor;
            if (u_boolMode == 1) {
                // Union — 綠色系
                color = vec3(0.3, 1.0, 0.5);
            } else if (u_boolMode == 2) {
                // Intersect — 黃色系
                color = vec3(1.0, 0.9, 0.3);
            } else if (u_boolMode == 3) {
                // Subtract — 紅色系
                color = vec3(1.0, 0.3, 0.3);
            }

            // 邊緣 vs 面
            float alpha;
            if (v_edgeMask > 0.5) {
                // 邊緣：亮線 + 脈衝
                alpha = u_edgeAlpha * (0.7 + 0.3 * pulse);
                color *= (1.0 + 0.5 * pulse);
            } else {
                // 面：半透明填充 + 輕微脈衝
                alpha = u_fillAlpha * (0.8 + 0.2 * pulse);

                // 菲涅爾效果（邊緣觀察角更亮）
                vec3 viewDir = normalize(v_worldPos);
                float fresnel = pow(1.0 - abs(dot(v_normal, viewDir)), 2.0);
                color += fresnel * 0.3;
            }

            // 網格線效果（世界空間像素對齊）
            vec3 grid = abs(fract(v_worldPos) - 0.5);
            float gridLine = min(min(grid.x, grid.y), grid.z);
            float gridFactor = 1.0 - smoothstep(0.0, 0.05, gridLine);
            color += gridFactor * 0.15;

            alpha *= distFade;
            fragColor = vec4(color, alpha);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  Ghost Block Shader（藍圖預覽幽靈方塊）
    // ══════════════════════════════════════════════════════════════

    private static final String GHOST_BLOCK_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec2 a_texCoord;
        layout(location = 3) in float a_collision; // 1.0 = 碰撞（紅色）, 0.0 = 正常

        uniform mat4 u_projMatrix;
        uniform mat4 u_viewMatrix;
        uniform vec3 u_cameraPos;
        uniform vec3 u_anchorPos;  // 藍圖放置錨點
        uniform float u_time;

        out vec3 v_worldPos;
        out vec3 v_normal;
        out vec2 v_texCoord;
        out float v_collision;
        out float v_viewDist;

        void main() {
            // 實際世界座標 = 相對座標 + 錨點
            vec3 worldPos = a_position + u_anchorPos;
            v_worldPos = worldPos;
            v_normal = normalize(a_normal);
            v_texCoord = a_texCoord;
            v_collision = a_collision;
            v_viewDist = distance(worldPos, u_cameraPos);

            // 呼吸動畫：幽靈方塊微微浮動
            float breathe = sin(u_time * 2.0 + worldPos.x * 0.5 + worldPos.z * 0.3) * 0.02;
            worldPos.y += breathe;

            vec3 viewPos = worldPos - u_cameraPos;
            gl_Position = u_projMatrix * u_viewMatrix * vec4(viewPos, 1.0);
        }
        """;

    private static final String GHOST_BLOCK_FRAG = """
        #version 330 core
        in vec3 v_worldPos;
        in vec3 v_normal;
        in vec2 v_texCoord;
        in float v_collision;
        in float v_viewDist;

        uniform sampler2D u_blockAtlas;  // 方塊材質圖集
        uniform float u_alpha;           // 全域透明度（預設 0.5）
        uniform float u_time;
        uniform int u_showCollision;     // 是否顯示碰撞標記

        layout(location = 0) out vec4 fragColor;

        void main() {
            // 基礎方塊顏色（從圖集採樣）
            vec4 texColor = texture(u_blockAtlas, v_texCoord);
            if (texColor.a < 0.01) discard;

            vec3 color = texColor.rgb;

            // 碰撞顯示（紅色疊加）
            if (u_showCollision > 0 && v_collision > 0.5) {
                // 紅色交叉線警告
                vec2 blockUV = fract(v_worldPos.xz);
                float cross1 = smoothstep(0.03, 0.0, abs(blockUV.x - blockUV.y));
                float cross2 = smoothstep(0.03, 0.0, abs(blockUV.x - (1.0 - blockUV.y)));
                float crossMask = max(cross1, cross2);
                color = mix(color, vec3(1.0, 0.15, 0.15), 0.5 + crossMask * 0.3);
            }

            // 幽靈特效：半透明 + 輕微脈衝
            float pulse = 0.85 + 0.15 * sin(u_time * 3.0);
            float alpha = u_alpha * pulse;

            // 邊緣高光（菲涅爾）
            vec3 viewDir = normalize(v_worldPos);
            float fresnel = pow(1.0 - abs(dot(v_normal, viewDir)), 3.0);

            // 正常模式 → 藍色邊框，碰撞模式 → 紅色邊框
            vec3 edgeColor;
            if (v_collision > 0.5 && u_showCollision > 0) {
                edgeColor = vec3(1.0, 0.2, 0.2);
            } else {
                edgeColor = vec3(0.3, 0.6, 1.0);
            }
            color += fresnel * edgeColor * 0.6;

            // 掃描線效果（由上到下的發光掃描）
            float scanY = fract(u_time * 0.3);
            float scanLine = smoothstep(0.02, 0.0, abs(fract(v_worldPos.y * 0.1) - scanY));
            color += scanLine * edgeColor * 0.4;

            // 方塊邊緣線
            vec3 edgeDist = abs(fract(v_worldPos) - 0.5);
            float edgeLine = 1.0 - smoothstep(0.42, 0.48, min(min(edgeDist.x, edgeDist.y), edgeDist.z));
            color += edgeLine * edgeColor * 0.3;

            fragColor = vec4(color, alpha);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  Volumetric Lighting Shader（體積光 / God Rays）
    // ══════════════════════════════════════════════════════════════

    private static final String VOLUMETRIC_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_depthTex;     // 深度緩衝
        uniform sampler2D u_shadowMap;    // 陰影貼圖
        uniform sampler2D u_sceneTex;     // 場景顏色
        uniform mat4 u_invProjView;       // 反投影矩陣
        uniform mat4 u_shadowProjView;    // 陰影空間矩陣
        uniform vec3 u_cameraPos;
        uniform vec3 u_sunDir;            // 太陽方向（歸一化）
        uniform vec3 u_sunColor;          // 太陽顏色
        uniform float u_fogDensity;       // 霧密度
        uniform float u_scatterStrength;  // 散射強度
        uniform int u_raySteps;           // 光線步進次數（預設 32）
        uniform float u_maxRayDist;       // 最大步進距離

        layout(location = 0) out vec4 fragColor;

        // Henyey-Greenstein 相函數
        float henyeyGreenstein(float cosTheta, float g) {
            float g2 = g * g;
            float denom = 1.0 + g2 - 2.0 * g * cosTheta;
            return (1.0 - g2) / (4.0 * 3.14159265 * pow(denom, 1.5));
        }

        // 重建世界座標
        vec3 reconstructWorldPos(vec2 uv) {
            float depth = texture(u_depthTex, uv).r;
            vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 worldPos = u_invProjView * clipPos;
            return worldPos.xyz / worldPos.w;
        }

        // 陰影採樣（PCF 軟陰影）
        float sampleShadow(vec3 worldPos) {
            vec4 shadowCoord = u_shadowProjView * vec4(worldPos, 1.0);
            shadowCoord.xyz /= shadowCoord.w;
            shadowCoord.xyz = shadowCoord.xyz * 0.5 + 0.5;

            if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
                shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
                shadowCoord.z > 1.0) {
                return 1.0; // 陰影貼圖外 = 受光
            }

            float currentDepth = shadowCoord.z;
            float shadowDepth = texture(u_shadowMap, shadowCoord.xy).r;
            float bias = 0.003;
            return currentDepth - bias > shadowDepth ? 0.0 : 1.0;
        }

        void main() {
            vec3 sceneColor = texture(u_sceneTex, v_texCoord).rgb;
            vec3 worldPos = reconstructWorldPos(v_texCoord);

            // 從相機到片段的光線
            vec3 rayDir = worldPos - u_cameraPos;
            float rayLength = min(length(rayDir), u_maxRayDist);
            rayDir = normalize(rayDir);

            // 視線與太陽方向的夾角（用於相函數）
            float cosTheta = dot(rayDir, u_sunDir);

            // Henyey-Greenstein 前向散射 (g=0.7) + 輕微後向散射 (g=-0.3)
            float scatter = mix(
                henyeyGreenstein(cosTheta, 0.7),
                henyeyGreenstein(cosTheta, -0.3),
                0.2
            );

            // Ray marching
            float stepSize = rayLength / float(u_raySteps);
            vec3 accumLight = vec3(0.0);
            float transmittance = 1.0;

            // 抖動起始位置（Bayer pattern 減少條紋）
            float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
            vec3 pos = u_cameraPos + rayDir * stepSize * dither;

            for (int i = 0; i < u_raySteps; i++) {
                pos += rayDir * stepSize;

                // 高度衰減（越高霧越少）
                float heightFactor = exp(-max(pos.y - 64.0, 0.0) * 0.015);

                // 陰影查詢
                float shadow = sampleShadow(pos);

                // 累積散射光
                float density = u_fogDensity * heightFactor;
                float lightContrib = shadow * scatter * density * u_scatterStrength;
                accumLight += u_sunColor * lightContrib * transmittance * stepSize;

                // Beer-Lambert 衰減
                transmittance *= exp(-density * stepSize);

                if (transmittance < 0.01) break;
            }

            // 合成
            vec3 result = sceneColor * transmittance + accumLight;
            fragColor = vec4(result, 1.0);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  SSR — Screen-Space Reflections（螢幕空間反射）
    //  Hi-Z Ray Marching + Binary Search 精修
    // ══════════════════════════════════════════════════════════════

    private static final String SSR_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_sceneTex;      // 場景顏色
        uniform sampler2D u_depthTex;      // 深度緩衝
        uniform sampler2D u_normalTex;     // GBuffer 法線（view space）
        uniform sampler2D u_materialTex;   // GBuffer 材質（r=metallic, g=roughness）
        uniform mat4 u_projMatrix;
        uniform mat4 u_invProjMatrix;
        uniform vec2 u_resolution;
        uniform float u_maxDistance;       // 最大追蹤距離（view space）
        uniform int u_maxSteps;            // 最大步進次數
        uniform int u_binarySteps;         // Binary search 精修次數
        uniform float u_thickness;         // 深度比較容差
        uniform float u_fadeEdge;          // 邊緣淡出距離

        layout(location = 0) out vec4 fragColor;

        // 從 UV + depth 重建 view-space position
        vec3 viewPosFromDepth(vec2 uv, float depth) {
            vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 viewPos = u_invProjMatrix * clipPos;
            return viewPos.xyz / viewPos.w;
        }

        // 投影到螢幕 UV
        vec3 projectToScreen(vec3 viewPos) {
            vec4 clipPos = u_projMatrix * vec4(viewPos, 1.0);
            clipPos.xyz /= clipPos.w;
            return vec3(clipPos.xy * 0.5 + 0.5, clipPos.z * 0.5 + 0.5);
        }

        void main() {
            // 材質資訊
            vec4 matData = texture(u_materialTex, v_texCoord);
            float metallic = matData.r;
            float roughness = matData.g;

            // 粗糙表面不做 SSR（效能優化）
            if (roughness > 0.7) {
                fragColor = texture(u_sceneTex, v_texCoord);
                return;
            }

            // 重建 view-space 資訊
            float depth = texture(u_depthTex, v_texCoord).r;
            if (depth >= 1.0) {
                fragColor = texture(u_sceneTex, v_texCoord);
                return;
            }

            vec3 viewPos = viewPosFromDepth(v_texCoord, depth);
            vec3 normal = normalize(texture(u_normalTex, v_texCoord).rgb * 2.0 - 1.0);

            // 反射方向
            vec3 viewDir = normalize(viewPos);
            vec3 reflDir = reflect(viewDir, normal);

            // ── Ray March in screen space ──
            vec3 startPos = viewPos;
            vec3 endPos = viewPos + reflDir * u_maxDistance;

            vec3 startScreen = projectToScreen(startPos);
            vec3 endScreen = projectToScreen(endPos);
            vec3 deltaScreen = endScreen - startScreen;

            // 步進向量
            float stepLen = length(deltaScreen.xy * u_resolution);
            int steps = min(u_maxSteps, int(stepLen));
            if (steps < 1) {
                fragColor = texture(u_sceneTex, v_texCoord);
                return;
            }
            vec3 stepDir = deltaScreen / float(steps);

            // Linear ray march
            vec3 currentPos = startScreen + stepDir; // 跳過起始點
            bool hit = false;
            vec3 hitPos = vec3(0.0);

            for (int i = 0; i < steps; i++) {
                // 邊界檢查
                if (currentPos.x < 0.0 || currentPos.x > 1.0 ||
                    currentPos.y < 0.0 || currentPos.y > 1.0) break;

                float sampleDepth = texture(u_depthTex, currentPos.xy).r;
                float diff = currentPos.z - sampleDepth;

                if (diff > 0.0 && diff < u_thickness) {
                    hit = true;
                    hitPos = currentPos;
                    break;
                }
                currentPos += stepDir;
            }

            // Binary search 精修
            if (hit) {
                vec3 lo = hitPos - stepDir;
                vec3 hi = hitPos;
                for (int i = 0; i < u_binarySteps; i++) {
                    vec3 mid = (lo + hi) * 0.5;
                    float sampleDepth = texture(u_depthTex, mid.xy).r;
                    float diff = mid.z - sampleDepth;
                    if (diff > 0.0) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                hitPos = (lo + hi) * 0.5;
            }

            // 合成
            vec3 sceneColor = texture(u_sceneTex, v_texCoord).rgb;

            if (!hit) {
                fragColor = vec4(sceneColor, 1.0);
                return;
            }

            vec3 reflColor = texture(u_sceneTex, hitPos.xy).rgb;

            // 邊緣淡出（UV 靠近螢幕邊緣時衰減）
            vec2 edgeDist = abs(hitPos.xy - 0.5) * 2.0;
            float edgeFade = 1.0 - smoothstep(1.0 - u_fadeEdge, 1.0, max(edgeDist.x, edgeDist.y));

            // 菲涅爾反射率（Schlick 近似）
            float cosTheta = max(dot(-viewDir, normal), 0.0);
            float f0 = mix(0.04, 1.0, metallic);
            float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);

            // 粗糙度衰減
            float roughFade = 1.0 - roughness;

            float reflStrength = fresnel * edgeFade * roughFade;
            vec3 result = mix(sceneColor, reflColor, reflStrength);

            fragColor = vec4(result, 1.0);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  DoF — Depth of Field（景深模糊）
    //  Circle of Confusion + Bokeh Disc 採樣
    // ══════════════════════════════════════════════════════════════

    private static final String DOF_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_sceneTex;     // 場景顏色
        uniform sampler2D u_depthTex;     // 深度
        uniform vec2 u_resolution;
        uniform float u_focusDist;        // 對焦距離（view space 線性深度）
        uniform float u_focusRange;       // 對焦清晰範圍
        uniform float u_maxBlurRadius;    // 最大模糊半徑（pixel）
        uniform float u_nearPlane;
        uniform float u_farPlane;
        uniform float u_aperture;         // 光圈大小（影響模糊程度）
        uniform int u_sampleCount;        // 採樣數（品質）
        uniform int u_bokehShape;         // 0=圓形, 1=六邊形

        layout(location = 0) out vec4 fragColor;

        // 深度線性化
        float linearDepth(float d) {
            float z = d * 2.0 - 1.0;
            return (2.0 * u_nearPlane * u_farPlane) / (u_farPlane + u_nearPlane - z * (u_farPlane - u_nearPlane));
        }

        // Circle of Confusion 計算
        float computeCoC(float linearZ) {
            float diff = abs(linearZ - u_focusDist);
            float coc = clamp((diff - u_focusRange) / u_focusRange, 0.0, 1.0);
            return coc * u_maxBlurRadius;
        }

        // Vogel disc 採樣分布（均勻圓盤）
        vec2 vogelDiskSample(int index, int count, float phi) {
            float goldenAngle = 2.399963;
            float r = sqrt(float(index) + 0.5) / sqrt(float(count));
            float theta = float(index) * goldenAngle + phi;
            return vec2(cos(theta), sin(theta)) * r;
        }

        // 六邊形判定（Bokeh 形狀）
        float hexagonalWeight(vec2 p) {
            vec2 a = abs(p);
            return step(a.x + a.y * 0.577, 1.0);
        }

        void main() {
            float depth = texture(u_depthTex, v_texCoord).r;

            // 天空不模糊
            if (depth >= 0.9999) {
                fragColor = texture(u_sceneTex, v_texCoord);
                return;
            }

            float linearZ = linearDepth(depth);
            float coc = computeCoC(linearZ);

            // CoC 太小 → 無需模糊
            if (coc < 0.5) {
                fragColor = texture(u_sceneTex, v_texCoord);
                return;
            }

            // 旋轉角偏移（抖動以減少帶狀紋理）
            float phi = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831;

            vec2 texelSize = 1.0 / u_resolution;
            vec3 accumColor = vec3(0.0);
            float accumWeight = 0.0;

            for (int i = 0; i < u_sampleCount; i++) {
                vec2 offset = vogelDiskSample(i, u_sampleCount, phi) * coc * texelSize;

                // 六邊形 Bokeh 形狀過濾
                float shapeWeight = 1.0;
                if (u_bokehShape == 1) {
                    shapeWeight = hexagonalWeight(offset / (coc * texelSize));
                }

                vec2 sampleUV = v_texCoord + offset;

                // 邊界 clamp
                sampleUV = clamp(sampleUV, vec2(0.0), vec2(1.0));

                // 採樣深度權重（前景保護 — 防止背景渲染到前景）
                float sampleDepth = texture(u_depthTex, sampleUV).r;
                float sampleLinearZ = linearDepth(sampleDepth);
                float sampleCoC = computeCoC(sampleLinearZ);

                // 只允許 CoC >= 當前點 CoC 的樣本滲透（防止尖銳前景被模糊）
                float depthWeight = step(linearZ - 0.1, sampleLinearZ) +
                                    step(coc * 0.5, sampleCoC);
                depthWeight = min(depthWeight, 1.0);

                vec3 sampleColor = texture(u_sceneTex, sampleUV).rgb;

                // Bokeh 亮點加權（明亮像素更突出 — 模擬真實鏡頭 Bokeh）
                float luminance = dot(sampleColor, vec3(0.2126, 0.7152, 0.0722));
                float bokehWeight = 1.0 + smoothstep(0.8, 2.0, luminance) * 3.0;

                float w = shapeWeight * depthWeight * bokehWeight;
                accumColor += sampleColor * w;
                accumWeight += w;
            }

            if (accumWeight > 0.0) {
                accumColor /= accumWeight;
            } else {
                accumColor = texture(u_sceneTex, v_texCoord).rgb;
            }

            // 平滑過渡（CoC 小的區域與原色混合）
            float blendFactor = smoothstep(0.5, 2.0, coc);
            vec3 originalColor = texture(u_sceneTex, v_texCoord).rgb;
            vec3 result = mix(originalColor, accumColor, blendFactor);

            fragColor = vec4(result, 1.0);
        }
        """;

    // ══════════════════════════════════════════════════════════════
    //  Contact Shadows（接觸陰影 — 近距離細節陰影增強）
    //  Screen-space ray march toward light
    // ══════════════════════════════════════════════════════════════

    private static final String CONTACT_SHADOW_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_depthTex;       // 深度緩衝
        uniform sampler2D u_ssaoTex;        // SSAO 結果（我們在其上疊加）
        uniform mat4 u_projMatrix;
        uniform mat4 u_invProjMatrix;
        uniform vec3 u_lightDirView;        // 光源方向（view space）
        uniform vec2 u_resolution;
        uniform float u_maxDistance;         // 最大追蹤距離（view space）
        uniform int u_steps;                // 步進次數
        uniform float u_thickness;          // 深度容差
        uniform float u_intensity;          // 陰影強度

        layout(location = 0) out vec4 fragColor;

        vec3 viewPosFromDepth(vec2 uv, float depth) {
            vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 viewPos = u_invProjMatrix * clipPos;
            return viewPos.xyz / viewPos.w;
        }

        vec3 projectToScreen(vec3 viewPos) {
            vec4 clipPos = u_projMatrix * vec4(viewPos, 1.0);
            clipPos.xyz /= clipPos.w;
            return vec3(clipPos.xy * 0.5 + 0.5, clipPos.z * 0.5 + 0.5);
        }

        void main() {
            float depth = texture(u_depthTex, v_texCoord).r;
            float ssao = texture(u_ssaoTex, v_texCoord).r;

            // 天空跳過
            if (depth >= 0.9999) {
                fragColor = vec4(ssao, ssao, ssao, 1.0);
                return;
            }

            // 重建 view-space 位置
            vec3 viewPos = viewPosFromDepth(v_texCoord, depth);

            // 向光源方向步進
            vec3 lightDir = normalize(u_lightDirView);
            vec3 stepVec = lightDir * (u_maxDistance / float(u_steps));

            float shadow = 0.0;
            vec3 currentPos = viewPos + stepVec; // 起始偏移避免自遮蔽

            // 抖動步進起始（減少條紋）
            float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
            currentPos += stepVec * dither * 0.5;

            for (int i = 0; i < u_steps; i++) {
                currentPos += stepVec;

                // 投影到螢幕空間
                vec3 screenPos = projectToScreen(currentPos);

                // 邊界檢查
                if (screenPos.x < 0.0 || screenPos.x > 1.0 ||
                    screenPos.y < 0.0 || screenPos.y > 1.0) break;

                float sampleDepth = texture(u_depthTex, screenPos.xy).r;
                float diff = screenPos.z - sampleDepth;

                if (diff > 0.0 && diff < u_thickness) {
                    // 距離衰減（越遠越弱）
                    float distFactor = 1.0 - float(i) / float(u_steps);
                    shadow = max(shadow, distFactor);
                    break; // 找到遮擋即停止
                }
            }

            // 合成：SSAO * (1 - contact shadow)
            float contactFactor = 1.0 - shadow * u_intensity;
            float combined = ssao * contactFactor;

            fragColor = vec4(combined, combined, combined, 1.0);
        }
        """;

    // ── Phase 6: Atmosphere Fragment Shader（Rayleigh / Mie 散射天空）──

    private static final String ATMOSPHERE_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform vec3  u_sunDir;
        uniform vec3  u_sunColor;
        uniform float u_earthRadius;
        uniform float u_atmosphereHeight;
        uniform vec3  u_rayleighCoeff;
        uniform float u_rayleighScale;
        uniform float u_mieCoeff;
        uniform float u_mieScale;
        uniform float u_mieG;
        uniform float u_dayFactor;
        uniform float u_time;
        uniform mat4  u_invProjView;

        const float PI = 3.14159265;
        const int   NUM_SAMPLES = 16;
        const int   NUM_LIGHT_SAMPLES = 8;

        // 重建世界空間射線方向
        vec3 getViewDir() {
            vec4 clipPos = vec4(v_texCoord * 2.0 - 1.0, 1.0, 1.0);
            vec4 worldPos = u_invProjView * clipPos;
            return normalize(worldPos.xyz / worldPos.w);
        }

        // Rayleigh 相位函數
        float rayleighPhase(float cosTheta) {
            return 3.0 / (16.0 * PI) * (1.0 + cosTheta * cosTheta);
        }

        // Mie 相位函數（Henyey-Greenstein）
        float miePhase(float cosTheta) {
            float g2 = u_mieG * u_mieG;
            float num = (1.0 - g2);
            float denom = pow(1.0 + g2 - 2.0 * u_mieG * cosTheta, 1.5);
            return num / (4.0 * PI * denom);
        }

        // 大氣密度（高度指數衰減）
        vec2 atmosphereDensity(float h) {
            return vec2(
                exp(-h / u_rayleighScale),
                exp(-h / u_mieScale)
            );
        }

        // 射線-球體交集（返回近/遠 t 值）
        float raySphereIntersect(vec3 origin, vec3 dir, float radius) {
            float a = dot(dir, dir);
            float b = 2.0 * dot(origin, dir);
            float c = dot(origin, origin) - radius * radius;
            float disc = b * b - 4.0 * a * c;
            if (disc < 0.0) return -1.0;
            return (-b + sqrt(disc)) / (2.0 * a);
        }

        void main() {
            vec3 viewDir = getViewDir();

            // 觀察者位置（地球表面）
            vec3 origin = vec3(0.0, u_earthRadius + 0.01, 0.0);
            float outerRadius = u_earthRadius + u_atmosphereHeight;

            float tMax = raySphereIntersect(origin, viewDir, outerRadius);
            if (tMax < 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

            float ds = tMax / float(NUM_SAMPLES);
            float cosTheta = dot(viewDir, u_sunDir);

            // 相位函數
            float phaseR = rayleighPhase(cosTheta);
            float phaseM = miePhase(cosTheta);

            // 累積光學深度 + 散射
            vec3 totalR = vec3(0.0);
            vec3 totalM = vec3(0.0);
            float opticalDepthR = 0.0;
            float opticalDepthM = 0.0;

            for (int i = 0; i < NUM_SAMPLES; i++) {
                vec3 samplePos = origin + viewDir * (float(i) + 0.5) * ds;
                float h = length(samplePos) - u_earthRadius;

                vec2 density = atmosphereDensity(h) * ds;
                opticalDepthR += density.x;
                opticalDepthM += density.y;

                // 光線方向光學深度
                float tSun = raySphereIntersect(samplePos, u_sunDir, outerRadius);
                float dsSun = tSun / float(NUM_LIGHT_SAMPLES);
                float odR_sun = 0.0, odM_sun = 0.0;
                for (int j = 0; j < NUM_LIGHT_SAMPLES; j++) {
                    vec3 sunSamplePos = samplePos + u_sunDir * (float(j) + 0.5) * dsSun;
                    float hSun = length(sunSamplePos) - u_earthRadius;
                    vec2 dSun = atmosphereDensity(hSun) * dsSun;
                    odR_sun += dSun.x;
                    odM_sun += dSun.y;
                }

                vec3 attenuation = exp(
                    -(u_rayleighCoeff * (opticalDepthR + odR_sun) +
                      u_mieCoeff * (opticalDepthM + odM_sun))
                );

                totalR += density.x * attenuation;
                totalM += density.y * attenuation;
            }

            vec3 sky = u_sunColor * (totalR * u_rayleighCoeff * phaseR +
                                     totalM * u_mieCoeff * phaseM);

            // 太陽光盤
            float sunDisk = smoothstep(0.9997, 0.9999, cosTheta);
            sky += u_sunColor * sunDisk * 2.0;

            // 星空（夜間）
            float nightFactor = 1.0 - u_dayFactor;
            if (nightFactor > 0.01) {
                vec3 dir = normalize(viewDir);
                float stars = step(0.998, fract(sin(dot(dir.xz * 100.0, vec2(12.9898, 78.233))) * 43758.5));
                stars *= step(0.0, dir.y); // 只在上半球
                sky += vec3(stars) * nightFactor * 0.8;
            }

            // Tone mapping（簡單 Reinhard）
            sky = sky / (sky + vec3(1.0));

            fragColor = vec4(sky, 1.0);
        }
        """;

    // ── Phase 6: Water Vertex Shader（Gerstner 波浪頂點位移）──

    private static final String WATER_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec2 a_texCoord;
        layout(location = 2) in vec3 a_normal;

        out vec3 v_worldPos;
        out vec2 v_texCoord;
        out vec3 v_normal;
        out vec4 v_clipPos;
        out float v_depth;

        uniform mat4 u_modelViewProj;
        uniform mat4 u_modelMatrix;
        uniform float u_waterLevel;
        uniform float u_animTime;

        // Gerstner 波浪（最多 4 組）
        struct Wave {
            float amplitude;
            float wavelength;
            float speed;
            float direction;
        };
        uniform Wave u_wave[4];
        uniform int  u_waveCount;

        vec3 gerstnerDisplacement(vec3 pos) {
            vec3 disp = vec3(0.0);
            vec3 norm = vec3(0.0, 1.0, 0.0);

            for (int i = 0; i < u_waveCount; i++) {
                float amp = u_wave[i].amplitude;
                float wl  = u_wave[i].wavelength;
                float spd = u_wave[i].speed;
                float dir = u_wave[i].direction;

                float dx = cos(dir);
                float dz = sin(dir);
                float k = 6.28318530 / wl;
                float phase = k * (dx * pos.x + dz * pos.z) - spd * u_animTime;
                float s = sin(phase);
                float c = cos(phase);

                // 垂直位移
                disp.y += amp * s;
                // 水平 Gerstner 位移（讓波峰更尖）
                float steepness = amp * k * 0.5;
                disp.x += steepness * dx * c;
                disp.z += steepness * dz * c;

                // 法線累積
                norm.x -= amp * k * dx * c;
                norm.z -= amp * k * dz * c;
            }
            v_normal = normalize(norm);
            return disp;
        }

        void main() {
            vec3 worldPos = (u_modelMatrix * vec4(a_position, 1.0)).xyz;
            vec3 disp = gerstnerDisplacement(worldPos);
            worldPos += disp;

            v_worldPos = worldPos;
            v_texCoord = a_texCoord;
            v_clipPos = u_modelViewProj * vec4(worldPos, 1.0);
            v_depth = worldPos.y - u_waterLevel;
            gl_Position = v_clipPos;
        }
        """;

    // ── Phase 6: Water Fragment Shader（PBR 水面 — 菲涅爾 + 吸收 + 泡沫 + 焦散）──

    private static final String WATER_FRAG = """
        #version 330 core
        in vec3 v_worldPos;
        in vec2 v_texCoord;
        in vec3 v_normal;
        in vec4 v_clipPos;
        in float v_depth;

        out vec4 fragColor;

        uniform vec3  u_cameraPos;
        uniform vec3  u_sunDir;
        uniform vec3  u_sunColor;
        uniform vec3  u_deepWaterColor;
        uniform vec3  u_shallowWaterColor;
        uniform vec3  u_absorptionCoeff;
        uniform float u_foamThreshold;
        uniform float u_foamIntensity;
        uniform float u_causticsIntensity;
        uniform float u_causticsScale;
        uniform float u_waterLevel;
        uniform float u_animTime;
        uniform sampler2D u_reflectionTex;
        uniform sampler2D u_depthTex;

        // Schlick 菲涅爾近似
        float schlickFresnel(float cosTheta) {
            float f0 = 0.02; // 水的 IOR ≈ 1.33 → F0 ≈ 0.02
            return f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
        }

        // 程序化焦散（Voronoi 變體）
        float caustics(vec2 uv, float time) {
            uv *= 8.0;
            float c = 0.0;
            for (int i = 0; i < 3; i++) {
                float t = time * (0.5 + float(i) * 0.3);
                vec2 p = uv + vec2(sin(t * 0.7), cos(t * 0.9));
                c += sin(p.x * 3.0 + t) * sin(p.y * 3.0 - t * 0.5) * 0.5 + 0.5;
            }
            return c / 3.0;
        }

        // 法線貼圖擾動（多層 UV 滾動）
        vec3 perturbNormal(vec3 baseNormal, vec3 worldPos, float time) {
            vec2 uv1 = worldPos.xz * 0.1 + vec2(time * 0.02, time * 0.015);
            vec2 uv2 = worldPos.xz * 0.05 + vec2(-time * 0.01, time * 0.02);

            // 程序化法線擾動（不需外部紋理）
            float nx = sin(uv1.x * 10.0) * 0.3 + sin(uv2.x * 7.0) * 0.2;
            float nz = cos(uv1.y * 10.0) * 0.3 + cos(uv2.y * 7.0) * 0.2;

            vec3 perturbed = normalize(baseNormal + vec3(nx, 0.0, nz) * 0.15);
            return perturbed;
        }

        void main() {
            vec3 normal = perturbNormal(v_normal, v_worldPos, u_animTime);
            vec3 viewDir = normalize(u_cameraPos - v_worldPos);

            // 菲涅爾
            float NdotV = max(dot(normal, viewDir), 0.0);
            float fresnel = schlickFresnel(NdotV);

            // 反射（螢幕空間取樣）
            vec2 screenUV = (v_clipPos.xy / v_clipPos.w) * 0.5 + 0.5;
            vec2 reflUV = vec2(screenUV.x, 1.0 - screenUV.y); // Y 翻轉
            reflUV += normal.xz * 0.03; // 法線擾動偏移
            vec3 reflection = texture(u_reflectionTex, reflUV).rgb;

            // Beer-Lambert 深度吸收
            float waterDepth = max(0.0, u_waterLevel - v_worldPos.y);
            vec3 absorption = exp(-u_absorptionCoeff * waterDepth * 3.0);
            vec3 waterColor = mix(u_deepWaterColor, u_shallowWaterColor, absorption);

            // 折射色（水體基礎色 * 吸收）
            vec3 refraction = waterColor * absorption;

            // 合成反射 + 折射
            vec3 color = mix(refraction, reflection, fresnel);

            // 高光（Blinn-Phong sun specular）
            vec3 halfVec = normalize(viewDir + u_sunDir);
            float spec = pow(max(dot(normal, halfVec), 0.0), 256.0);
            color += u_sunColor * spec * 0.5;

            // 泡沫（岸邊 / 淺水區）
            if (waterDepth < u_foamThreshold) {
                float foamFactor = 1.0 - waterDepth / u_foamThreshold;
                foamFactor *= u_foamIntensity;
                // 泡沫噪聲
                float foamNoise = fract(sin(dot(v_worldPos.xz * 5.0, vec2(12.9898, 78.233))) * 43758.5);
                foamFactor *= smoothstep(0.3, 0.7, foamNoise);
                color = mix(color, vec3(0.9, 0.95, 1.0), foamFactor);
            }

            // 水下焦散
            if (waterDepth > 0.1) {
                float causticsVal = caustics(v_worldPos.xz * u_causticsScale, u_animTime);
                color += vec3(causticsVal) * u_causticsIntensity * absorption;
            }

            // 透明度（邊緣淡出）
            float alpha = mix(0.6, 0.95, fresnel);
            alpha = min(alpha, smoothstep(0.0, 0.3, waterDepth));

            fragColor = vec4(color, alpha);
        }
        """;

    // ── Phase 6: Particle Vertex Shader（GPU Instanced Billboard）──

    private static final String PARTICLE_VERT = """
        #version 330 core
        // 基礎 quad 頂點（billboard）
        layout(location = 0) in vec2 a_quadPos; // [-0.5, 0.5]

        // Per-instance 資料（來自 instance VBO）
        layout(location = 1) in vec3  i_position;
        layout(location = 2) in vec3  i_velocity;
        layout(location = 3) in vec4  i_color;
        layout(location = 4) in float i_size;
        layout(location = 5) in float i_life;
        layout(location = 6) in float i_maxLife;
        layout(location = 7) in float i_type;

        out vec2  v_texCoord;
        out vec4  v_color;
        out float v_life;
        out float v_type;

        uniform mat4 u_viewProj;
        uniform vec3 u_cameraRight;
        uniform vec3 u_cameraUp;

        void main() {
            // 生命比例（用於衰減）
            float lifeRatio = i_life / max(i_maxLife, 0.001);
            v_life = lifeRatio;
            v_type = i_type;

            // 大小隨生命衰減
            float size = i_size * smoothstep(0.0, 0.1, lifeRatio) *
                         smoothstep(0.0, 0.2, 1.0 - lifeRatio);

            // Billboard：用相機 right/up 向量展開 quad
            vec3 worldPos = i_position
                + u_cameraRight * a_quadPos.x * size
                + u_cameraUp    * a_quadPos.y * size;

            // 顏色（alpha 隨生命衰減）
            v_color = i_color;
            v_color.a *= smoothstep(0.0, 0.15, lifeRatio) *
                         smoothstep(0.0, 0.3, 1.0 - lifeRatio);

            v_texCoord = a_quadPos + 0.5;
            gl_Position = u_viewProj * vec4(worldPos, 1.0);
        }
        """;

    // ── Phase 6: Particle Fragment Shader（程序化粒子外觀）──

    private static final String PARTICLE_FRAG = """
        #version 330 core
        in vec2  v_texCoord;
        in vec4  v_color;
        in float v_life;
        in float v_type;

        out vec4 fragColor;

        const float TYPE_SPARK    = 0.0;
        const float TYPE_DUST     = 1.0;
        const float TYPE_FRAGMENT = 2.0;
        const float TYPE_GLOW     = 3.0;
        const float TYPE_SNOW     = 4.0;
        const float TYPE_BUBBLE   = 5.0;
        const float TYPE_RING     = 6.0;

        void main() {
            vec2 uv = v_texCoord * 2.0 - 1.0; // [-1, 1]
            float dist = length(uv);

            float alpha = 0.0;
            vec3 color = v_color.rgb;

            // 根據粒子類型選擇形狀
            float type = floor(v_type + 0.5);

            if (type == TYPE_SPARK) {
                // 火花 — 柔和圓點 + 亮核
                alpha = exp(-dist * dist * 4.0);
                color += vec3(0.3, 0.15, 0.0) * exp(-dist * dist * 2.0);
            }
            else if (type == TYPE_DUST) {
                // 灰塵 — 柔和模糊圓
                alpha = smoothstep(1.0, 0.3, dist) * 0.6;
            }
            else if (type == TYPE_FRAGMENT) {
                // 碎片 — 方形（菱形旋轉）
                float diamond = abs(uv.x) + abs(uv.y);
                alpha = smoothstep(1.0, 0.8, diamond);
            }
            else if (type == TYPE_GLOW) {
                // 發光 — 強輻射衰減
                alpha = exp(-dist * dist * 2.0);
                color *= 1.5; // 加亮
            }
            else if (type == TYPE_SNOW) {
                // 雪花 — 六角暗示
                float angle = atan(uv.y, uv.x);
                float hex = cos(angle * 3.0) * 0.15 + 0.85;
                alpha = smoothstep(1.0, 0.5, dist / hex) * 0.8;
                color = mix(color, vec3(1.0), 0.3);
            }
            else if (type == TYPE_BUBBLE) {
                // 氣泡 — 環形 + 高光
                float ring = smoothstep(0.8, 0.9, dist) - smoothstep(0.9, 1.0, dist);
                float fill = smoothstep(1.0, 0.7, dist) * 0.15;
                float highlight = exp(-pow(length(uv - vec2(-0.3, 0.3)), 2.0) * 20.0);
                alpha = ring * 0.6 + fill + highlight * 0.4;
                color = mix(color, vec3(1.0), highlight * 0.5);
            }
            else if (type == TYPE_RING) {
                // 確認環 — 擴展環
                float ringRadius = mix(0.3, 0.9, 1.0 - v_life);
                float ring = smoothstep(ringRadius - 0.1, ringRadius, dist)
                           - smoothstep(ringRadius, ringRadius + 0.1, dist);
                alpha = ring * v_life;
            }

            // 最終 alpha 合成
            alpha *= v_color.a;
            if (alpha < 0.01) discard;

            fragColor = vec4(color, alpha);
        }
        """;

    // ── Phase 7: CSM Fragment Shader（級聯陰影查詢 + PCSS 軟陰影）──

    private static final String CSM_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_depthTex;
        uniform sampler2D u_normalTex;
        uniform sampler2D u_shadowMap[4];
        uniform mat4  u_lightViewProj[4];
        uniform float u_cascadeSplit[4];
        uniform vec3  u_lightDir;
        uniform mat4  u_invViewProj;
        uniform float u_nearPlane;
        uniform float u_farPlane;
        uniform float u_shadowIntensity;

        // PCSS 參數
        const float PCSS_LIGHT_SIZE = 0.02;
        const int   PCSS_BLOCKER_SAMPLES = 16;
        const int   PCSS_PCF_SAMPLES = 25;

        // 線性化深度
        float linearizeDepth(float d) {
            return (2.0 * u_nearPlane * u_farPlane) /
                   (u_farPlane + u_nearPlane - d * (u_farPlane - u_nearPlane));
        }

        // 重建世界位置
        vec3 reconstructWorldPos(vec2 uv, float depth) {
            vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 worldPos = u_invViewProj * clipPos;
            return worldPos.xyz / worldPos.w;
        }

        // 選擇級聯
        int selectCascade(float viewDepth) {
            for (int i = 0; i < 4; i++) {
                if (viewDepth < u_cascadeSplit[i]) return i;
            }
            return 3;
        }

        // Vogel Disc 取樣分布（PCSS 用）
        vec2 vogelDisk(int sampleIndex, int totalSamples, float phi) {
            float r = sqrt(float(sampleIndex) + 0.5) / sqrt(float(totalSamples));
            float theta = float(sampleIndex) * 2.39996323 + phi; // golden angle
            return vec2(cos(theta), sin(theta)) * r;
        }

        // PCSS: 步驟 1 — 搜尋遮擋者平均深度
        float searchBlockerDepth(sampler2D shadowMap, vec3 shadowCoord, float searchRadius) {
            float phi = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5);
            float blockerSum = 0.0;
            int blockerCount = 0;

            for (int i = 0; i < PCSS_BLOCKER_SAMPLES; i++) {
                vec2 offset = vogelDisk(i, PCSS_BLOCKER_SAMPLES, phi) * searchRadius;
                float sampleDepth = texture(shadowMap, shadowCoord.xy + offset).r;
                if (sampleDepth < shadowCoord.z - 0.001) {
                    blockerSum += sampleDepth;
                    blockerCount++;
                }
            }

            if (blockerCount == 0) return -1.0; // 無遮擋
            return blockerSum / float(blockerCount);
        }

        // PCSS: 步驟 2 — 可變半徑 PCF
        float pcfShadow(sampler2D shadowMap, vec3 shadowCoord, float filterRadius) {
            float phi = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5);
            float shadow = 0.0;

            for (int i = 0; i < PCSS_PCF_SAMPLES; i++) {
                vec2 offset = vogelDisk(i, PCSS_PCF_SAMPLES, phi) * filterRadius;
                float sampleDepth = texture(shadowMap, shadowCoord.xy + offset).r;
                shadow += (shadowCoord.z - 0.001 > sampleDepth) ? 1.0 : 0.0;
            }

            return shadow / float(PCSS_PCF_SAMPLES);
        }

        void main() {
            float depth = texture(u_depthTex, v_texCoord).r;
            if (depth >= 1.0) { fragColor = vec4(1.0); return; } // 天空不投影

            float viewDepth = linearizeDepth(depth);
            int cascade = selectCascade(viewDepth);
            vec3 worldPos = reconstructWorldPos(v_texCoord, depth);

            // 轉換到光空間
            vec4 lightSpacePos = u_lightViewProj[cascade] * vec4(worldPos, 1.0);
            vec3 shadowCoord = lightSpacePos.xyz / lightSpacePos.w;
            shadowCoord = shadowCoord * 0.5 + 0.5;

            // 邊界檢查
            if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
                shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
                shadowCoord.z > 1.0) {
                fragColor = vec4(1.0);
                return;
            }

            // PCSS 流程
            float searchRadius = PCSS_LIGHT_SIZE * shadowCoord.z;
            float blockerDepth = searchBlockerDepth(u_shadowMap[cascade], shadowCoord, searchRadius);

            float shadow;
            if (blockerDepth < 0.0) {
                shadow = 0.0; // 無遮擋 → 全亮
            } else {
                // 軟陰影半徑 = lightSize * (receiver - blocker) / blocker
                float penumbraWidth = PCSS_LIGHT_SIZE *
                    (shadowCoord.z - blockerDepth) / blockerDepth;
                penumbraWidth = max(penumbraWidth, 0.001);
                shadow = pcfShadow(u_shadowMap[cascade], shadowCoord, penumbraWidth);
            }

            // 級聯邊界柔化（避免硬切換）
            float cascadeFade = 1.0;
            if (cascade < 3) {
                float splitDist = u_cascadeSplit[cascade];
                float fadeStart = splitDist * 0.9;
                if (viewDepth > fadeStart) {
                    cascadeFade = 1.0 - (viewDepth - fadeStart) / (splitDist - fadeStart);
                    // 混合下一級聯
                    vec4 nextLightPos = u_lightViewProj[cascade + 1] * vec4(worldPos, 1.0);
                    vec3 nextSC = nextLightPos.xyz / nextLightPos.w * 0.5 + 0.5;
                    float nextShadow = pcfShadow(u_shadowMap[cascade + 1], nextSC, 0.002);
                    shadow = mix(nextShadow, shadow, cascadeFade);
                }
            }

            float shadowFactor = 1.0 - shadow * u_shadowIntensity;
            fragColor = vec4(shadowFactor, shadowFactor, shadowFactor, 1.0);
        }
        """;

    // ── Phase 7: Cloud Fragment Shader（程序化體積雲 — Ray March + FBM 噪聲）──

    private static final String CLOUD_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform mat4  u_invProjView;
        uniform vec3  u_cameraPos;
        uniform vec3  u_sunDir;
        uniform vec3  u_sunColor;
        uniform float u_cloudBottom;
        uniform float u_cloudTop;
        uniform float u_cloudThickness;
        uniform float u_coverage;
        uniform float u_densityMul;
        uniform float u_cloudType;
        uniform float u_time;
        uniform float u_windX;
        uniform float u_windZ;

        const float PI = 3.14159265;
        const int   MARCH_STEPS = 64;
        const int   LIGHT_STEPS = 6;

        // ── 雜訊函數 ──

        // Hash（程序化隨機）
        float hash(vec3 p) {
            p = fract(p * vec3(0.1031, 0.1030, 0.0973));
            p += dot(p, p.yxz + 33.33);
            return fract((p.x + p.y) * p.z);
        }

        // Value Noise 3D
        float noise3D(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f); // smoothstep

            float n000 = hash(i);
            float n100 = hash(i + vec3(1,0,0));
            float n010 = hash(i + vec3(0,1,0));
            float n110 = hash(i + vec3(1,1,0));
            float n001 = hash(i + vec3(0,0,1));
            float n101 = hash(i + vec3(1,0,1));
            float n011 = hash(i + vec3(0,1,1));
            float n111 = hash(i + vec3(1,1,1));

            float nx00 = mix(n000, n100, f.x);
            float nx10 = mix(n010, n110, f.x);
            float nx01 = mix(n001, n101, f.x);
            float nx11 = mix(n011, n111, f.x);

            float nxy0 = mix(nx00, nx10, f.y);
            float nxy1 = mix(nx01, nx11, f.y);

            return mix(nxy0, nxy1, f.z);
        }

        // FBM（分形布朗運動）— Perlin 風格
        float fbm(vec3 p) {
            float value = 0.0;
            float amp = 0.5;
            float freq = 1.0;
            for (int i = 0; i < 5; i++) {
                value += amp * noise3D(p * freq);
                freq *= 2.0;
                amp *= 0.5;
            }
            return value;
        }

        // Worley 噪聲（細胞雜訊 — 侵蝕用）
        float worley(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            float minDist = 1.0;
            for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
            for (int z = -1; z <= 1; z++) {
                vec3 neighbor = vec3(float(x), float(y), float(z));
                vec3 point = hash(i + neighbor) * vec3(1.0); // 隨機位置
                float dist = length(neighbor + point - f);
                minDist = min(minDist, dist);
            }
            return minDist;
        }

        // ── 雲密度 ──

        // 高度梯度（控制雲在不同高度的密度分布）
        float heightGradient(float h, float type) {
            // type 0 = 層雲（底部厚）, type 1 = 積雲（中間厚頂部尖）
            float stratus = smoothstep(0.0, 0.1, h) * smoothstep(0.4, 0.2, h);
            float cumulus = smoothstep(0.0, 0.15, h) * smoothstep(1.0, 0.6, h);
            return mix(stratus, cumulus, type);
        }

        float cloudDensity(vec3 pos) {
            // 標準化高度 [0, 1]
            float h = (pos.y - u_cloudBottom) / u_cloudThickness;
            if (h < 0.0 || h > 1.0) return 0.0;

            // 風場偏移
            vec3 windOffset = vec3(u_windX, 0.0, u_windZ);
            vec3 samplePos = pos + windOffset;

            // 基礎形狀（低頻 FBM）
            float baseShape = fbm(samplePos * 0.003);

            // 高度梯度
            float hGrad = heightGradient(h, u_cloudType);

            // Coverage remap
            float density = baseShape * hGrad;
            density = smoothstep(1.0 - u_coverage, 1.0, density);

            // 高頻侵蝕（Worley — 讓邊緣蓬鬆）
            float erosion = worley(samplePos * 0.01) * 0.3;
            density = max(0.0, density - erosion);

            return density * u_densityMul;
        }

        // ── 射線-平面交集 ──

        float rayPlaneIntersect(vec3 origin, vec3 dir, float planeY) {
            if (abs(dir.y) < 0.0001) return -1.0;
            return (planeY - origin.y) / dir.y;
        }

        // ── Henyey-Greenstein 雙瓣 ──

        float hgPhase(float cosTheta, float g) {
            float g2 = g * g;
            return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
        }

        float dualLobePhase(float cosTheta) {
            // 前瓣（銀邊）+ 後瓣（暗核）
            return mix(hgPhase(cosTheta, 0.8), hgPhase(cosTheta, -0.3), 0.3);
        }

        // ── 主渲染 ──

        void main() {
            // 重建射線方向
            vec4 clipPos = vec4(v_texCoord * 2.0 - 1.0, 1.0, 1.0);
            vec4 worldDir = u_invProjView * clipPos;
            vec3 rayDir = normalize(worldDir.xyz / worldDir.w - u_cameraPos);

            // 計算射線與雲層包圍盒的交集
            float tBottom = rayPlaneIntersect(u_cameraPos, rayDir, u_cloudBottom);
            float tTop = rayPlaneIntersect(u_cameraPos, rayDir, u_cloudTop);

            if (tBottom < 0.0 && tTop < 0.0) { fragColor = vec4(0.0); return; }

            float tStart, tEnd;
            if (u_cameraPos.y < u_cloudBottom) {
                tStart = max(tBottom, 0.0);
                tEnd = tTop;
            } else if (u_cameraPos.y > u_cloudTop) {
                tStart = max(tTop, 0.0);
                tEnd = tBottom;
            } else {
                // 在雲層內部
                tStart = 0.0;
                tEnd = max(tBottom, tTop);
            }

            if (tEnd <= tStart || tEnd < 0.0) { fragColor = vec4(0.0); return; }
            tStart = max(tStart, 0.0);

            // Ray March 參數
            float dt = (tEnd - tStart) / float(MARCH_STEPS);
            float cosTheta = dot(rayDir, u_sunDir);
            float phase = dualLobePhase(cosTheta);

            // 累積
            vec3 scatteredLight = vec3(0.0);
            float transmittance = 1.0;

            for (int i = 0; i < MARCH_STEPS; i++) {
                if (transmittance < 0.01) break;

                float t = tStart + (float(i) + 0.5) * dt;
                vec3 pos = u_cameraPos + rayDir * t;

                float density = cloudDensity(pos);
                if (density <= 0.001) continue;

                // Beer-Lambert 衰減
                float sampleExtinction = density * dt * 0.05;

                // 光線 march（向太陽方向估算光量）
                float lightTransmittance = 1.0;
                float dtLight = u_cloudThickness / float(LIGHT_STEPS);
                for (int j = 0; j < LIGHT_STEPS; j++) {
                    vec3 lightPos = pos + u_sunDir * (float(j) + 0.5) * dtLight;
                    float lightDensity = cloudDensity(lightPos);
                    lightTransmittance *= exp(-lightDensity * dtLight * 0.05);
                }

                // 散射光：太陽光 × 光通透率 × 相位函數
                vec3 sunScatter = u_sunColor * lightTransmittance * phase;

                // 環境光（天空散射）
                vec3 ambient = vec3(0.3, 0.4, 0.6) * 0.15;

                // 累積
                vec3 luminance = (sunScatter + ambient) * density;
                float sampleTransmittance = exp(-sampleExtinction);

                scatteredLight += luminance * transmittance * dt * 0.05;
                transmittance *= sampleTransmittance;
            }

            float alpha = 1.0 - transmittance;

            // Pre-multiplied alpha（合成用）
            fragColor = vec4(scatteredLight * alpha, alpha);
        }
        """;

    // ── Phase 7: Cinematic Post-FX Fragment Shader（暈影 + 色差 + 動態模糊）──

    private static final String CINEMATIC_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_mainTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_velocityTex;
        uniform float u_vignetteIntensity;
        uniform float u_vignetteRadius;
        uniform float u_chromaticAberration;
        uniform float u_motionBlurStrength;
        uniform int   u_motionBlurSamples;
        uniform float u_filmGrain;
        uniform float u_time;

        // 暈影（Vignette）
        vec3 applyVignette(vec3 color, vec2 uv) {
            vec2 center = uv - 0.5;
            float dist = length(center);
            float vignette = smoothstep(u_vignetteRadius, u_vignetteRadius - 0.3, dist);
            return color * mix(1.0 - u_vignetteIntensity, 1.0, vignette);
        }

        // 色差（Chromatic Aberration）
        vec3 applyChromaticAberration(vec2 uv) {
            vec2 dir = (uv - 0.5) * u_chromaticAberration;
            float r = texture(u_mainTex, uv + dir).r;
            float g = texture(u_mainTex, uv).g;
            float b = texture(u_mainTex, uv - dir).b;
            return vec3(r, g, b);
        }

        // 動態模糊（Motion Blur — 基於 velocity buffer）
        vec3 applyMotionBlur(vec3 color, vec2 uv) {
            vec2 velocity = texture(u_velocityTex, uv).rg;
            velocity *= u_motionBlurStrength;

            if (length(velocity) < 0.001) return color;

            vec3 result = color;
            float totalWeight = 1.0;

            for (int i = 1; i < u_motionBlurSamples; i++) {
                float t = float(i) / float(u_motionBlurSamples - 1) - 0.5;
                vec2 sampleUV = uv + velocity * t;
                sampleUV = clamp(sampleUV, vec2(0.001), vec2(0.999));

                float weight = 1.0 - abs(t) * 0.5;
                result += texture(u_mainTex, sampleUV).rgb * weight;
                totalWeight += weight;
            }

            return result / totalWeight;
        }

        // 底片顆粒（Film Grain）
        vec3 applyFilmGrain(vec3 color, vec2 uv) {
            float grain = fract(sin(dot(uv * vec2(12.9898, 78.233) + u_time,
                                        vec2(12.9898, 78.233))) * 43758.5);
            grain = (grain - 0.5) * u_filmGrain;
            return color + vec3(grain);
        }

        void main() {
            vec2 uv = v_texCoord;

            // 色差（如果啟用）
            vec3 color;
            if (u_chromaticAberration > 0.0001) {
                color = applyChromaticAberration(uv);
            } else {
                color = texture(u_mainTex, uv).rgb;
            }

            // 動態模糊（如果啟用）
            if (u_motionBlurStrength > 0.0001) {
                color = applyMotionBlur(color, uv);
            }

            // 暈影
            color = applyVignette(color, uv);

            // 底片顆粒
            if (u_filmGrain > 0.0001) {
                color = applyFilmGrain(color, uv);
            }

            fragColor = vec4(color, 1.0);
        }
        """;

    // ── Phase 8: Velocity Buffer Fragment Shader（螢幕空間運動向量）──

    private static final String VELOCITY_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec2 fragVelocity;

        uniform sampler2D u_depthTex;
        uniform mat4  u_invViewProj;
        uniform mat4  u_prevViewProj;

        void main() {
            float depth = texture(u_depthTex, v_texCoord).r;

            // 天空不計算速度
            if (depth >= 1.0) { fragVelocity = vec2(0.0); return; }

            // 重建當前世界位置
            vec4 clipPos = vec4(v_texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 worldPos = u_invViewProj * clipPos;
            worldPos /= worldPos.w;

            // 投影到上一幀螢幕位置
            vec4 prevClip = u_prevViewProj * worldPos;
            vec2 prevScreen = prevClip.xy / prevClip.w * 0.5 + 0.5;

            // 速度 = 當前 - 上一幀
            fragVelocity = v_texCoord - prevScreen;
        }
        """;

    // ── Phase 8: Color Grading Fragment Shader（3D LUT 查詢 + 時段色偏）──

    private static final String COLOR_GRADE_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_mainTex;
        uniform sampler3D u_lutTex;
        uniform float     u_lutSize;
        uniform float     u_dayFactor;
        uniform float     u_intensity; // LUT 混合強度（0=原始, 1=完全 LUT）

        // 時段色偏
        vec3 applyTimeTint(vec3 color, float dayFactor) {
            // dayFactor: 0=半夜, 0.25=日出, 0.5=正午, 0.75=黃昏
            vec3 tint = vec3(0.0);

            // 日出（0.2~0.35）：暖金偏橘
            float sunrise = smoothstep(0.15, 0.25, dayFactor) - smoothstep(0.25, 0.35, dayFactor);
            tint += vec3(0.08, 0.04, -0.02) * sunrise;

            // 正午（0.4~0.6）：微暖中性
            float noon = smoothstep(0.35, 0.45, dayFactor) - smoothstep(0.55, 0.65, dayFactor);
            tint += vec3(0.02, 0.01, 0.0) * noon;

            // 黃昏（0.65~0.8）：紫粉偏暖
            float sunset = smoothstep(0.6, 0.7, dayFactor) - smoothstep(0.75, 0.85, dayFactor);
            tint += vec3(0.06, 0.02, 0.04) * sunset;

            // 夜晚（0.85~1.0, 0.0~0.15）：冷藍
            float night = 1.0 - smoothstep(0.15, 0.25, dayFactor)
                        + smoothstep(0.8, 0.9, dayFactor);
            night = min(night, 1.0);
            tint += vec3(-0.03, -0.01, 0.05) * night;

            return color + tint;
        }

        void main() {
            vec3 color = texture(u_mainTex, v_texCoord).rgb;

            // 3D LUT 查詢（需要半 texel 偏移避免邊緣插值錯誤）
            float scale = (u_lutSize - 1.0) / u_lutSize;
            float offset = 0.5 / u_lutSize;
            vec3 lutCoord = color * scale + offset;
            vec3 graded = texture(u_lutTex, lutCoord).rgb;

            // 混合原始色和 LUT 色
            color = mix(color, graded, u_intensity);

            // 時段色偏
            color = applyTimeTint(color, u_dayFactor);

            fragColor = vec4(color, 1.0);
        }
        """;

    // ── Phase 8: Debug Visualization Fragment Shader（深度/級聯/LOD 視覺化）──

    private static final String DEBUG_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_depthTex;
        uniform float u_nearPlane;
        uniform float u_farPlane;
        uniform int   u_debugMode; // 0=depth, 1=cascade, 2=LOD

        // CSM 分割距離
        uniform float u_cascadeSplit[4];

        // 線性化深度
        float linearizeDepth(float d) {
            return (2.0 * u_nearPlane * u_farPlane) /
                   (u_farPlane + u_nearPlane - d * (u_farPlane - u_nearPlane));
        }

        // 級聯色（紅/綠/藍/黃 分別代表 cascade 0/1/2/3）
        vec3 cascadeColor(int cascade) {
            if (cascade == 0) return vec3(1.0, 0.2, 0.2); // 紅
            if (cascade == 1) return vec3(0.2, 1.0, 0.2); // 綠
            if (cascade == 2) return vec3(0.2, 0.2, 1.0); // 藍
            return vec3(1.0, 1.0, 0.2);                   // 黃
        }

        void main() {
            float depth = texture(u_depthTex, v_texCoord).r;

            if (u_debugMode == 0) {
                // 深度視覺化（Turbo colormap 近似）
                float linear = linearizeDepth(depth) / u_farPlane;
                linear = clamp(linear, 0.0, 1.0);

                // 簡化 Turbo colormap
                vec3 color;
                if (linear < 0.25) {
                    float t = linear / 0.25;
                    color = mix(vec3(0.18, 0.0, 0.29), vec3(0.0, 0.42, 0.87), t);
                } else if (linear < 0.5) {
                    float t = (linear - 0.25) / 0.25;
                    color = mix(vec3(0.0, 0.42, 0.87), vec3(0.0, 0.84, 0.44), t);
                } else if (linear < 0.75) {
                    float t = (linear - 0.5) / 0.25;
                    color = mix(vec3(0.0, 0.84, 0.44), vec3(0.93, 0.73, 0.0), t);
                } else {
                    float t = (linear - 0.75) / 0.25;
                    color = mix(vec3(0.93, 0.73, 0.0), vec3(0.72, 0.0, 0.0), t);
                }

                fragColor = vec4(color, 1.0);

            } else if (u_debugMode == 1) {
                // CSM 級聯視覺化
                float viewDepth = linearizeDepth(depth);
                int cascade = 3;
                for (int i = 0; i < 4; i++) {
                    if (viewDepth < u_cascadeSplit[i]) {
                        cascade = i;
                        break;
                    }
                }

                vec3 color = cascadeColor(cascade);
                // 半透明覆蓋（保留場景可見度）
                fragColor = vec4(color, 0.3);

            } else {
                // 預設：直接輸出深度灰度
                float linear = linearizeDepth(depth) / u_farPlane;
                fragColor = vec4(vec3(linear), 1.0);
            }
        }
        """;

    // ── Phase 9: SSGI Fragment Shader（螢幕空間全域光照）──

    private static final String SSGI_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_albedoTex;
        uniform sampler2D u_normalTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_historyTex;
        uniform float u_gameTime;
        uniform int   u_frameIndex;
        uniform float u_giIntensity;
        uniform float u_giRadius;
        uniform int   u_giSamples;

        const float PI = 3.14159265;

        // 隨機旋轉（per-pixel jitter）
        float hash2D(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5);
        }

        vec2 vogelDisk(int idx, int total, float phi) {
            float r = sqrt(float(idx) + 0.5) / sqrt(float(total));
            float theta = float(idx) * 2.39996323 + phi;
            return vec2(cos(theta), sin(theta)) * r;
        }

        // 線性化深度
        float linearDepth(float d) {
            float near = 0.1;
            float far = 1024.0;
            return (2.0 * near * far) / (far + near - d * (far - near));
        }

        void main() {
            vec3 albedo = texture(u_albedoTex, v_texCoord).rgb;
            vec3 normal = texture(u_normalTex, v_texCoord).rgb * 2.0 - 1.0;
            float depth = texture(u_depthTex, v_texCoord).r;

            if (depth >= 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

            float linearD = linearDepth(depth);

            // 取樣半徑（隨深度縮放）
            float radius = u_giRadius / linearD;
            radius = min(radius, 0.1); // 限制最大螢幕空間半徑

            // Jitter 旋轉（每幀不同 → temporal accumulation 收斂）
            float phi = hash2D(v_texCoord * 1000.0 + float(u_frameIndex) * 0.618) * PI * 2.0;

            // 半球取樣
            vec3 giAccum = vec3(0.0);
            float aoAccum = 0.0;

            for (int i = 0; i < u_giSamples; i++) {
                vec2 offset = vogelDisk(i, u_giSamples, phi) * radius;
                vec2 sampleUV = v_texCoord + offset;

                if (sampleUV.x < 0.0 || sampleUV.x > 1.0 ||
                    sampleUV.y < 0.0 || sampleUV.y > 1.0) continue;

                float sampleDepth = texture(u_depthTex, sampleUV).r;
                vec3 sampleAlbedo = texture(u_albedoTex, sampleUV).rgb;
                vec3 sampleNormal = texture(u_normalTex, sampleUV).rgb * 2.0 - 1.0;

                float sampleLinearD = linearDepth(sampleDepth);
                float depthDiff = linearD - sampleLinearD;

                // 深度範圍檢查（太遠的不算）
                float rangeCheck = smoothstep(0.0, 1.0, u_giRadius / abs(depthDiff));

                // 法線權重（朝向採樣方向的表面貢獻更多）
                vec3 sampleDir = normalize(vec3(offset, depthDiff));
                float normalWeight = max(0.0, dot(normal, sampleDir));

                // 累積間接光
                giAccum += sampleAlbedo * normalWeight * rangeCheck;
                aoAccum += (depthDiff > 0.01) ? rangeCheck : 0.0;
            }

            giAccum /= float(u_giSamples);
            aoAccum /= float(u_giSamples);

            // 間接光 = 採樣到的反射光 × 本身 albedo
            vec3 indirectLight = giAccum * albedo * u_giIntensity;

            // AO 因子
            float ao = 1.0 - aoAccum * 0.5;

            // Temporal accumulation（與歷史幀混合）
            vec3 history = texture(u_historyTex, v_texCoord).rgb;
            vec3 result = mix(history, indirectLight * ao, 0.1); // 90% 歷史 + 10% 當前

            fragColor = vec4(result, 1.0);
        }
        """;

    // ── Phase 9: Fog Fragment Shader（距離霧 + 高度霧 + Inscattering）──

    private static final String FOG_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform sampler2D u_mainTex;
        uniform sampler2D u_depthTex;
        uniform float u_distanceDensity;
        uniform float u_heightDensity;
        uniform float u_heightFalloff;
        uniform float u_heightBase;
        uniform float u_cameraY;
        uniform float u_maxFog;
        uniform float u_inscattering;
        uniform vec3  u_fogColor;
        uniform vec3  u_sunDir;
        uniform float u_nearPlane;
        uniform float u_farPlane;

        float linearizeDepth(float d) {
            return (2.0 * u_nearPlane * u_farPlane) /
                   (u_farPlane + u_nearPlane - d * (u_farPlane - u_nearPlane));
        }

        void main() {
            vec3 sceneColor = texture(u_mainTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;

            if (depth >= 1.0) { fragColor = vec4(sceneColor, 1.0); return; }

            float linearD = linearizeDepth(depth);

            // 1. 距離霧（指數衰減）
            float distFog = 1.0 - exp(-linearD * u_distanceDensity);

            // 2. 高度霧（指數高度衰減）
            // 密度沿 Y 軸指數衰減：density = base * exp(-height * falloff)
            float heightAboveBase = max(u_cameraY - u_heightBase, 0.0);
            float heightFogDensity = u_heightDensity * exp(-heightAboveBase * u_heightFalloff);
            float heightFog = 1.0 - exp(-linearD * heightFogDensity);

            // 3. 合成霧因子
            float fogFactor = max(distFog, heightFog);
            fogFactor = min(fogFactor, u_maxFog);

            // 4. Inscattering（太陽方向的光暈效果）
            // 使用 view direction 與 sun direction 的夾角
            vec2 screenDir = v_texCoord * 2.0 - 1.0;
            vec3 approxViewDir = normalize(vec3(screenDir, 1.0));
            float sunDot = max(dot(approxViewDir, u_sunDir), 0.0);
            float inscatter = pow(sunDot, 8.0) * u_inscattering;

            // Inscattering 讓霧色偏向太陽色（暖白）
            vec3 inscatterColor = u_fogColor + vec3(0.2, 0.15, 0.05) * inscatter;

            // 5. 最終合成
            vec3 finalColor = mix(sceneColor, inscatterColor, fogFactor);

            fragColor = vec4(finalColor, 1.0);
        }
        """;

    // ── Phase 9: Lens Flare Fragment Shader（程序化鏡頭光暈）──

    private static final String LENS_FLARE_FRAG = """
        #version 330 core
        in vec2 v_texCoord;
        out vec4 fragColor;

        uniform vec2  u_sunPos;        // NDC 空間 (-1~1)
        uniform float u_visibility;
        uniform float u_time;
        uniform int   u_ghostCount;
        uniform float u_ghostDispersal;
        uniform float u_haloRadius;
        uniform float u_intensity;

        const float PI = 3.14159265;

        // 程序化圓形光斑
        float circle(vec2 uv, vec2 center, float radius, float softness) {
            float d = length(uv - center);
            return smoothstep(radius + softness, radius - softness, d);
        }

        // 程序化環形
        float ring(vec2 uv, vec2 center, float radius, float width) {
            float d = length(uv - center);
            return smoothstep(radius + width, radius, d) *
                   smoothstep(radius - width, radius, d);
        }

        // 色差折射（不同波長不同偏移）
        vec3 chromaticGhost(vec2 uv, vec2 ghostCenter, float ghostSize) {
            float r = circle(uv, ghostCenter + vec2(ghostSize * 0.02, 0.0), ghostSize, ghostSize * 0.5);
            float g = circle(uv, ghostCenter, ghostSize, ghostSize * 0.5);
            float b = circle(uv, ghostCenter - vec2(ghostSize * 0.02, 0.0), ghostSize, ghostSize * 0.5);
            return vec3(r, g, b);
        }

        // 星芒（Starburst — 6 角放射線）
        float starburst(vec2 uv, vec2 center) {
            vec2 d = uv - center;
            float angle = atan(d.y, d.x);
            float dist = length(d);
            float rays = abs(sin(angle * 3.0)); // 6 條射線
            float falloff = exp(-dist * 8.0);
            return rays * falloff * 0.5;
        }

        void main() {
            vec2 uv = v_texCoord * 2.0 - 1.0; // [-1, 1]
            vec2 sunUV = u_sunPos;

            float totalFlare = 0.0;
            vec3 flareColor = vec3(0.0);

            // 1. Ghost 鬼影（沿太陽-螢幕中心軸線的多個光斑）
            vec2 ghostAxis = -sunUV; // 反方向
            for (int i = 0; i < u_ghostCount; i++) {
                float t = float(i + 1) * u_ghostDispersal;
                vec2 ghostPos = sunUV + ghostAxis * t;
                float ghostSize = 0.03 + float(i) * 0.015;

                // 色差鬼影
                vec3 ghost = chromaticGhost(uv, ghostPos, ghostSize);
                float ghostAlpha = (1.0 - float(i) * 0.15);
                flareColor += ghost * ghostAlpha * 0.3;
            }

            // 2. Halo 光環（太陽周圍的大環）
            float halo = ring(uv, sunUV, u_haloRadius, 0.02);
            flareColor += vec3(0.8, 0.7, 0.5) * halo * 0.4;

            // 3. 核心光斑（太陽中心的強亮點）
            float core = circle(uv, sunUV, 0.02, 0.03);
            flareColor += vec3(1.0, 0.95, 0.8) * core * 2.0;

            // 4. 星芒
            float burst = starburst(uv, sunUV);
            flareColor += vec3(0.9, 0.85, 0.7) * burst * 0.6;

            // 5. 漫射光暈（大範圍柔和光暈）
            float glow = exp(-length(uv - sunUV) * 3.0);
            flareColor += vec3(0.6, 0.5, 0.3) * glow * 0.2;

            // 合成
            flareColor *= u_visibility * u_intensity;

            fragColor = vec4(flareColor, 1.0);
        }
        """;

    // ═══════════════════════════════════════════════════════════════════
    //  Phase 10: Weather Shaders — 天氣系統 GLSL
    // ═══════════════════════════════════════════════════════════════════

    // ── Rain Vertex Shader（GPU Instanced 雨滴） ──
    private static final String RAIN_VERT = """
        #version 330 core
        layout(location = 0) in vec4 a_quadVert;  // xy=pos, zw=uv
        layout(location = 1) in vec3 a_instPos;   // instance 位置
        layout(location = 2) in vec4 a_instData;  // velY, life, alpha, streakLen

        uniform mat4 u_viewProj;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;

        out vec2 v_uv;
        out float v_alpha;
        out float v_life;

        void main() {
            float streakLen = a_instData.w;
            float alpha = a_instData.z;
            float life = a_instData.y;

            // Billboard 面向相機（只繞 Y 軸）
            vec3 worldPos = a_instPos + u_cameraPos;
            vec3 toCamera = normalize(u_cameraPos - worldPos);
            vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), toCamera));

            // 雨滴拉伸（垂直方向 stretch by streakLen）
            vec3 offset = right * a_quadVert.x + vec3(0.0, a_quadVert.y * streakLen, 0.0);
            vec3 finalPos = worldPos + offset;

            gl_Position = u_viewProj * vec4(finalPos, 1.0);
            v_uv = a_quadVert.zw;
            v_alpha = alpha * smoothstep(0.0, 0.1, life);
            v_life = life;
        }
        """;

    // ── Rain Fragment Shader ──
    private static final String RAIN_FRAG = """
        #version 330 core
        in vec2 v_uv;
        in float v_alpha;
        in float v_life;

        uniform float u_intensity;
        uniform float u_gameTime;
        uniform float u_wetness;

        out vec4 fragColor;

        void main() {
            // 雨滴形狀：垂直線段，中間亮邊緣暗
            float centerDist = abs(v_uv.x - 0.5) * 2.0;
            float shape = 1.0 - smoothstep(0.0, 1.0, centerDist);

            // 垂直漸變（頂端淡，底端亮）
            float vertFade = v_uv.y * 0.5 + 0.5;

            // 半透明白色雨滴
            vec3 rainColor = vec3(0.7, 0.75, 0.85);
            float alpha = shape * vertFade * v_alpha * u_intensity * 0.6;

            fragColor = vec4(rainColor, alpha);
        }
        """;

    // ── Snow Vertex Shader（GPU Instanced 雪花） ──
    private static final String SNOW_VERT = """
        #version 330 core
        layout(location = 0) in vec4 a_quadVert;  // xy=pos, zw=uv
        layout(location = 1) in vec3 a_instPos;   // instance 位置
        layout(location = 2) in vec4 a_instData;  // velY, life, alpha, size
        layout(location = 3) in float a_wobble;   // wobble phase

        uniform mat4 u_viewProj;
        uniform vec3 u_cameraPos;
        uniform float u_gameTime;

        out vec2 v_uv;
        out float v_alpha;
        out float v_size;

        void main() {
            float size = a_instData.w;
            float alpha = a_instData.z;
            float life = a_instData.y;

            vec3 worldPos = a_instPos + u_cameraPos;

            // Billboard（完全面向相機）
            vec3 toCamera = normalize(u_cameraPos - worldPos);
            vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), toCamera));
            vec3 up = cross(toCamera, right);

            vec3 offset = (right * a_quadVert.x + up * a_quadVert.y) * size;
            vec3 finalPos = worldPos + offset;

            gl_Position = u_viewProj * vec4(finalPos, 1.0);
            v_uv = a_quadVert.zw;
            v_alpha = alpha * smoothstep(0.0, 0.3, life);
            v_size = size;
        }
        """;

    // ── Snow Fragment Shader ──
    private static final String SNOW_FRAG = """
        #version 330 core
        in vec2 v_uv;
        in float v_alpha;
        in float v_size;

        uniform float u_intensity;
        uniform float u_gameTime;
        uniform float u_snowCoverage;

        out vec4 fragColor;

        void main() {
            // 圓形雪花（距中心距離）
            vec2 center = v_uv - 0.5;
            float dist = length(center);
            float circle = 1.0 - smoothstep(0.3, 0.5, dist);

            // 六角結晶紋理（簡化版）
            float angle = atan(center.y, center.x);
            float crystal = 0.8 + 0.2 * cos(angle * 6.0);

            float shape = circle * crystal;

            vec3 snowColor = vec3(0.95, 0.97, 1.0);
            float alpha = shape * v_alpha * u_intensity * 0.7;

            fragColor = vec4(snowColor, alpha);
        }
        """;

    // ── Lightning Fragment Shader（全螢幕閃電 + 螢幕閃光） ──
    private static final String LIGHTNING_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform float u_gameTime;
        uniform float u_flashIntensity;
        uniform float u_screenWidth;
        uniform float u_screenHeight;

        // Bolt 資料（最多 3 道閃電）
        struct Bolt {
            float triggerTime;
            float offsetX;
            float offsetY;
            float seed;
        };
        uniform Bolt u_bolt[3];

        out vec4 fragColor;

        // 簡易 hash
        float hash(float n) { return fract(sin(n) * 43758.5453); }

        // L-system 風格閃電路徑
        float lightning(vec2 uv, vec2 start, float seed, float timeSinceTrigger) {
            if (timeSinceTrigger > 0.5) return 0.0;

            float fade = exp(-timeSinceTrigger * 8.0);
            float totalLight = 0.0;

            vec2 p = start;
            vec2 dir = vec2(0.0, -1.0); // 向下
            float segLen = 0.08;

            for (int i = 0; i < 12; i++) {
                // 隨機偏折
                float rnd = hash(seed + float(i) * 7.31);
                float angle = (rnd - 0.5) * 1.2;
                dir = vec2(
                    dir.x * cos(angle) - dir.y * sin(angle),
                    dir.x * sin(angle) + dir.y * cos(angle)
                );
                vec2 next = p + dir * segLen;

                // 線段距離
                vec2 pa = uv - p;
                vec2 ba = next - p;
                float t = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                float d = length(pa - ba * t);

                float glow = 0.003 / (d + 0.001);
                totalLight += glow;

                // 分支（30% 機率）
                if (rnd > 0.7 && i < 8) {
                    float bAngle = (hash(seed + float(i) * 13.7) - 0.5) * 2.0;
                    vec2 bDir = vec2(
                        dir.x * cos(bAngle) - dir.y * sin(bAngle),
                        dir.x * sin(bAngle) + dir.y * cos(bAngle)
                    );
                    vec2 bEnd = p + bDir * segLen * 0.6;
                    vec2 bpa = uv - p;
                    vec2 bba = bEnd - p;
                    float bt = clamp(dot(bpa, bba) / dot(bba, bba), 0.0, 1.0);
                    float bd = length(bpa - bba * bt);
                    totalLight += 0.001 / (bd + 0.001) * 0.5;
                }

                p = next;
            }

            return totalLight * fade;
        }

        void main() {
            vec3 scene = texture(u_inputTex, v_texCoord).rgb;

            // 閃電 bolt 累積
            float boltLight = 0.0;
            for (int i = 0; i < 3; i++) {
                float timeSince = u_gameTime - u_bolt[i].triggerTime;
                if (timeSince >= 0.0 && timeSince < 0.5) {
                    vec2 start = vec2(0.5 + u_bolt[i].offsetX, u_bolt[i].offsetY);
                    boltLight += lightning(v_texCoord, start, u_bolt[i].seed, timeSince);
                }
            }

            // 閃電顏色（冷白偏藍）
            vec3 boltColor = vec3(0.8, 0.85, 1.0) * boltLight;

            // 全螢幕閃光（白色 flash）
            vec3 flash = vec3(1.0) * u_flashIntensity * 0.5;

            fragColor = vec4(scene + boltColor + flash, 1.0);
        }
        """;

    // ── Aurora Fragment Shader（程序化極光帷幕） ──
    private static final String AURORA_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_depthTex;
        uniform float u_auroraTime;
        uniform float u_auroraBrightness;
        uniform float u_windOffsetX;
        uniform float u_windOffsetZ;
        uniform float u_auroraHeight;
        uniform float u_auroraThickness;
        uniform float u_screenWidth;
        uniform float u_screenHeight;

        out vec4 fragColor;

        // 簡易 3D 噪聲
        float hash3(vec3 p) {
            p = fract(p * vec3(443.897, 441.423, 437.195));
            p += dot(p, p.yzx + 19.19);
            return fract((p.x + p.y) * p.z);
        }

        float noise3(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);

            float a = hash3(i);
            float b = hash3(i + vec3(1,0,0));
            float c = hash3(i + vec3(0,1,0));
            float d = hash3(i + vec3(1,1,0));
            float e = hash3(i + vec3(0,0,1));
            float g = hash3(i + vec3(1,0,1));
            float h = hash3(i + vec3(0,1,1));
            float k = hash3(i + vec3(1,1,1));

            return mix(
                mix(mix(a, b, f.x), mix(c, d, f.x), f.y),
                mix(mix(e, g, f.x), mix(h, k, f.x), f.y),
                f.z
            );
        }

        float fbm(vec3 p) {
            float v = 0.0;
            float amp = 0.5;
            for (int i = 0; i < 5; i++) {
                v += noise3(p) * amp;
                p *= 2.0;
                amp *= 0.5;
            }
            return v;
        }

        // 極光色彩映射（高度 → 綠→青→紫）
        vec3 auroraColor(float h) {
            vec3 green  = vec3(0.1, 0.9, 0.3);
            vec3 cyan   = vec3(0.1, 0.7, 0.8);
            vec3 purple = vec3(0.5, 0.2, 0.8);
            if (h < 0.5) return mix(green, cyan, h * 2.0);
            return mix(cyan, purple, (h - 0.5) * 2.0);
        }

        void main() {
            vec3 scene = texture(u_inputTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;

            // 只在天空像素疊加（depth ≈ 1.0）
            if (depth < 0.9999) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            // 極光帷幕 — 使用 UV.y 映射到高度
            float skyY = v_texCoord.y;

            // 只在上半部天空顯示
            if (skyY < 0.3) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            float h = (skyY - 0.3) / 0.7; // 0~1 映射

            // FBM 噪聲場
            vec3 noisePos = vec3(
                v_texCoord.x * 3.0 + u_windOffsetX * 0.01,
                h * 2.0 + u_auroraTime * 0.05,
                u_windOffsetZ * 0.01
            );
            float n = fbm(noisePos);

            // 帷幕形狀（薄帶 + 噪聲調制）
            float curtain = smoothstep(0.3, 0.6, n) * smoothstep(0.0, 0.3, h) * smoothstep(1.0, 0.7, h);

            // 亮度脈衝
            float pulse = 0.7 + 0.3 * sin(u_auroraTime * 0.3 + v_texCoord.x * 5.0);

            // 合成
            vec3 aColor = auroraColor(h) * curtain * pulse * u_auroraBrightness;

            fragColor = vec4(scene + aColor, 1.0);
        }
        """;

    // ── Wet PBR Fragment Shader（濕潤表面修正） ──
    private static final String WET_PBR_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_normalTex;   // GBuffer normal
        uniform sampler2D u_materialTex; // GBuffer material (roughness/metallic)

        uniform float u_wetness;         // 全域濕潤度 0~1
        uniform float u_snowCoverage;    // 積雪覆蓋度 0~1
        uniform float u_gameTime;

        out vec4 fragColor;

        void main() {
            vec3 scene = texture(u_inputTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;

            // 天空不修正
            if (depth > 0.9999) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            vec3 normal = texture(u_normalTex, v_texCoord).rgb * 2.0 - 1.0;
            vec4 material = texture(u_materialTex, v_texCoord);
            float roughness = material.g;
            float metallic = material.b;

            // ── 濕潤效果 ──
            // 濕潤降低粗糙度（水膜使表面更光滑）
            float wetFactor = u_wetness * max(0.0, normal.y); // 只影響朝上的表面
            float wetRoughness = mix(roughness, roughness * 0.3, wetFactor);

            // 濕潤提高反射率（水的折射率 ~1.33）
            float wetSpecular = mix(0.0, 0.3, wetFactor);

            // 濕潤使表面變暗（水吸收部分光線）
            vec3 wetScene = scene * mix(1.0, 0.7, wetFactor);

            // 增加反射光澤
            wetScene += vec3(wetSpecular) * smoothstep(0.5, 0.0, wetRoughness);

            // ── 積雪效果 ──
            float snowFactor = u_snowCoverage * max(0.0, normal.y) * 0.8;

            // 積雪白色覆蓋 + 提高粗糙度
            vec3 snowColor = vec3(0.92, 0.94, 0.98);
            vec3 finalColor = mix(wetScene, snowColor, snowFactor);

            // 微觀紋理：積雪表面的微光閃爍
            float sparkle = fract(sin(dot(v_texCoord * 200.0, vec2(12.9898, 78.233)) + u_gameTime) * 43758.5);
            sparkle = smoothstep(0.97, 1.0, sparkle) * snowFactor * 0.3;
            finalColor += vec3(sparkle);

            fragColor = vec4(finalColor, 1.0);
        }
        """;

    // ═══════════════════════════════════════════════════════════════════
    //  Phase 11: Material Enhancement Shaders — 材質增強 GLSL
    // ═══════════════════════════════════════════════════════════════════

    // ── SSS Fragment Shader（可分離次表面散射） ──
    private static final String SSS_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_materialTex;

        uniform float u_sssWidth;
        uniform float u_sssStrength;
        uniform vec2 u_direction; // (1/w, 0) 或 (0, 1/h)
        uniform float u_screenWidth;
        uniform float u_screenHeight;

        out vec4 fragColor;

        // Burley diffusion profile 近似權重（7 tap）
        const int KERNEL_SIZE = 7;
        const float weights[7] = float[](0.006, 0.061, 0.242, 0.383, 0.242, 0.061, 0.006);
        const float offsets[7] = float[](-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0);

        // SSS 剖面顏色（模擬皮膚/樹葉/蠟的散射色彩）
        vec3 sssProfile(float dist) {
            // Christensen-Burley 雙指數近似
            vec3 red   = vec3(0.233, 0.455, 0.649) * exp(-dist * 1.5);
            vec3 green = vec3(0.100, 0.336, 0.344) * exp(-dist * 3.0);
            return red + green;
        }

        void main() {
            vec4 center = texture(u_inputTex, v_texCoord);
            float centerDepth = texture(u_depthTex, v_texCoord).r;
            vec4 material = texture(u_materialTex, v_texCoord);

            // SSS mask：material.a 通道（0=非SSS, >0=SSS材質）
            float sssMask = material.a;

            // 非 SSS 材質直接輸出
            if (sssMask < 0.01) {
                fragColor = center;
                return;
            }

            vec3 result = vec3(0.0);
            float totalWeight = 0.0;

            for (int i = 0; i < KERNEL_SIZE; i++) {
                vec2 sampleUV = v_texCoord + u_direction * offsets[i] * u_sssWidth;
                float sampleDepth = texture(u_depthTex, sampleUV).r;

                // 深度感知：跨物體邊界不模糊
                float depthDiff = abs(centerDepth - sampleDepth);
                float depthWeight = 1.0 - smoothstep(0.0, 0.005, depthDiff);

                vec3 sampleColor = texture(u_inputTex, sampleUV).rgb;
                float w = weights[i] * depthWeight;

                // SSS 剖面色彩調制
                vec3 profile = sssProfile(abs(offsets[i]) * u_sssWidth * 10.0);
                result += sampleColor * profile * w;
                totalWeight += w;
            }

            result /= max(totalWeight, 0.001);

            // 混合原始與 SSS
            vec3 finalColor = mix(center.rgb, result, sssMask * u_sssStrength);

            fragColor = vec4(finalColor, center.a);
        }
        """;

    // ── Anisotropic Reflection Fragment Shader（GGX 各向異性 BRDF） ──
    private static final String ANISOTROPIC_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_normalTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_materialTex;
        uniform sampler2D u_tangentTex;

        uniform float u_anisotropyStrength;
        uniform float u_gameTime;
        uniform float u_screenWidth;
        uniform float u_screenHeight;

        out vec4 fragColor;

        // GGX Anisotropic NDF (Burley 2012)
        float D_GGX_Anisotropic(vec3 N, vec3 H, vec3 T, vec3 B, float ax, float ay) {
            float NoH = max(dot(N, H), 0.0);
            float ToH = dot(T, H);
            float BoH = dot(B, H);

            float d = (ToH * ToH) / (ax * ax) + (BoH * BoH) / (ay * ay) + NoH * NoH;
            return 1.0 / (3.14159 * ax * ay * d * d);
        }

        void main() {
            vec3 scene = texture(u_inputTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;
            vec4 material = texture(u_materialTex, v_texCoord);

            // 各向異性材質判定：metallic > 0.5 且 roughness 方向有差異
            float metallic = material.b;
            float roughness = material.g;
            float anisotropyFlag = material.r; // r 通道編碼各向異性程度

            // 天空或非各向異性材質
            if (depth > 0.9999 || anisotropyFlag < 0.01 || metallic < 0.3) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            vec3 normal = normalize(texture(u_normalTex, v_texCoord).rgb * 2.0 - 1.0);
            vec4 tangentData = texture(u_tangentTex, v_texCoord);
            vec3 tangent = normalize(tangentData.rgb * 2.0 - 1.0);
            vec3 bitangent = cross(normal, tangent);

            // 各向異性粗糙度
            float aniso = anisotropyFlag * u_anisotropyStrength;
            float ax = roughness * (1.0 + aniso);
            float ay = roughness * (1.0 - aniso * 0.5);

            // 從深度重建視角向量（螢幕空間 → 世界空間近似）
            // v_texCoord → NDC → 視角方向
            vec2 ndc = v_texCoord * 2.0 - 1.0;
            vec3 V = normalize(vec3(ndc.x * (u_screenWidth / u_screenHeight), ndc.y, -1.0));
            // 假設太陽方向作為主光源（由 u_gameTime 驅動日夜）
            float sunAngle = u_gameTime * 0.01;
            vec3 L = normalize(vec3(cos(sunAngle) * 0.5, 0.7, sin(sunAngle) * 0.5));
            vec3 H = normalize(V + L);

            float D = D_GGX_Anisotropic(normal, H, tangent, bitangent, ax, ay);

            // Fresnel-Schlick 近似（金屬材質的各向異性反射）
            float VoH = max(dot(V, H), 0.0);
            vec3 F0 = mix(vec3(0.04), scene, metallic);
            vec3 F = F0 + (1.0 - F0) * pow(1.0 - VoH, 5.0);

            // 各向異性高光 = D * F（NDF × Fresnel）
            vec3 specBoost = vec3(D) * F * aniso * 0.5;
            vec3 specColor = scene + specBoost;

            // 拉絲紋理效果 — 修正：沿世界空間 tangent 方向計算
            // 使用 tangent 在螢幕空間的投影作為條紋方向
            vec2 tangentScreen = normalize(tangent.xy);
            vec2 fragWorldUV = gl_FragCoord.xy;
            float streak = 0.5 + 0.5 * sin(dot(fragWorldUV, tangentScreen) * 3.14159);
            streak = mix(1.0, streak, aniso * 0.08);

            vec3 finalColor = specColor * streak;
            fragColor = vec4(finalColor, 1.0);
        }
        """;

    // ── POM Fragment Shader（視差遮蔽映射） ──
    private static final String POM_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_inputTex;
        uniform sampler2D u_depthTex;
        uniform sampler2D u_normalTex;
        uniform sampler2D u_materialTex;
        uniform sampler2D u_albedoTex;

        uniform float u_pomScale;
        uniform int u_pomSteps;
        uniform int u_pomRefinementSteps;
        uniform float u_pomFadeDistance;
        uniform float u_gameTime;
        uniform float u_screenWidth;
        uniform float u_screenHeight;
        uniform mat4 u_invViewProj;

        out vec4 fragColor;

        // 從深度值重建 view-space 距離（線性化）
        float linearDepth(float d) {
            float near = 0.05;
            float far = 1024.0;
            return near * far / (far - d * (far - near));
        }

        // 從螢幕空間重建相機空間視角向量
        // 參考 Tatarchuk 2006 "Practical Parallax Occlusion Mapping"
        vec3 reconstructViewDir(vec2 texCoord, float depth) {
            // NDC 座標
            vec2 ndc = texCoord * 2.0 - 1.0;
            float aspect = u_screenWidth / u_screenHeight;

            // 利用 NDC 和深度重建相機空間方向
            // 假設標準透視投影（FOV ≈ 70°），tan(fov/2) ≈ 0.7
            float tanHalfFov = 0.7;
            vec3 viewPos = vec3(
                ndc.x * aspect * tanHalfFov,
                ndc.y * tanHalfFov,
                -1.0
            );

            return normalize(viewPos);
        }

        void main() {
            vec3 scene = texture(u_inputTex, v_texCoord).rgb;
            float depth = texture(u_depthTex, v_texCoord).r;
            vec4 material = texture(u_materialTex, v_texCoord);

            // 高度圖在 material alpha 通道（0=平, 1=最高）
            float heightMap = material.a;

            // 天空或無高度資訊
            if (depth > 0.9999 || heightMap < 0.01) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            // LOD 距離漸隱
            float dist = linearDepth(depth);
            float fadeFactor = 1.0 - smoothstep(u_pomFadeDistance * 0.5, u_pomFadeDistance, dist);

            if (fadeFactor < 0.01) {
                fragColor = vec4(scene, 1.0);
                return;
            }

            vec3 normal = normalize(texture(u_normalTex, v_texCoord).rgb * 2.0 - 1.0);

            // 正確的視差向量計算：
            // 重建相機空間視角向量，投影到切線空間（TBN）得到 UV 偏移方向
            vec3 viewDir = reconstructViewDir(v_texCoord, depth);

            // 建構簡化 TBN（從 normal 推導 tangent/bitangent）
            vec3 up = abs(normal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
            vec3 tangent = normalize(cross(up, normal));
            vec3 bitangent = cross(normal, tangent);

            // 視角在切線空間的投影 → UV 偏移方向
            vec2 viewDir2D = vec2(dot(viewDir, tangent), dot(viewDir, bitangent));
            // 沿視線方向縮放（掠射角時偏移更大 — Tatarchuk 正確行為）
            float viewDotNormal = abs(dot(viewDir, normal));
            viewDir2D /= max(viewDotNormal, 0.1);

            // Steep Parallax Mapping — 步進搜尋
            // 自適應步數：掠射角時增加步數以避免分層穿幫
            int adaptiveSteps = int(mix(float(u_pomSteps), float(u_pomSteps) * 2.0,
                                       1.0 - viewDotNormal));
            float layerDepth = 1.0 / float(adaptiveSteps);
            float currentLayerDepth = 0.0;
            vec2 deltaUV = viewDir2D * u_pomScale / float(adaptiveSteps);
            vec2 currentUV = v_texCoord;
            float currentHeight = texture(u_materialTex, currentUV).a;

            for (int i = 0; i < adaptiveSteps; i++) {
                if (currentLayerDepth >= currentHeight) break;
                currentUV -= deltaUV;
                currentHeight = texture(u_materialTex, currentUV).a;
                currentLayerDepth += layerDepth;
            }

            // 二分精修
            vec2 prevUV = currentUV + deltaUV;
            float prevHeight = texture(u_materialTex, prevUV).a;
            float prevLayerDepth = currentLayerDepth - layerDepth;

            for (int i = 0; i < u_pomRefinementSteps; i++) {
                vec2 midUV = (currentUV + prevUV) * 0.5;
                float midHeight = texture(u_materialTex, midUV).a;
                float midLayer = (currentLayerDepth + prevLayerDepth) * 0.5;

                if (midHeight > midLayer) {
                    currentUV = midUV;
                    currentLayerDepth = midLayer;
                } else {
                    prevUV = midUV;
                    prevLayerDepth = midLayer;
                }
            }

            // 使用修正 UV 重採樣 albedo
            vec3 pomColor = texture(u_albedoTex, currentUV).rgb;

            // 自遮蔽（近似陰影）
            float shadow = smoothstep(0.0, 0.1, currentLayerDepth - currentHeight);
            float selfShadow = 1.0 - shadow * 0.4;

            pomColor *= selfShadow;

            // 與原始場景混合（淡入/淡出）
            vec3 finalColor = mix(scene, pomColor, fadeFactor);

            fragColor = vec4(finalColor, 1.0);
        }
        """;

    // ── Occlusion Query AABB Proxy（最小化 — 僅需深度寫入）──

    private static final String OCCLUSION_QUERY_VERT = """
        #version 330 core
        layout(location = 0) in vec3 a_position;
        uniform mat4 u_viewProj;
        uniform vec3 u_bboxMin;
        uniform vec3 u_bboxSize;
        void main() {
            // 單位立方體 (0~1) 縮放至實際 AABB
            vec3 worldPos = a_position * u_bboxSize + u_bboxMin;
            gl_Position = u_viewProj * vec4(worldPos, 1.0);
        }
        """;

    private static final String OCCLUSION_QUERY_FRAG = """
        #version 330 core
        void main() {
            // 僅做深度測試，不輸出顏色（colorMask 已關閉）
        }
        """;

    // ═══════════════════════════════════════════════════════════════════
    //  Phase 12: Advanced Rendering Shaders
    // ═══════════════════════════════════════════════════════════════════

    // ── VCT Composite — 錐體追蹤間接光照合成 ──
    private static final String VCT_COMPOSITE_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_sceneTex;       // 直接光照結果
        uniform sampler2D u_normalTex;      // GBuffer normal
        uniform sampler2D u_depthTex;       // GBuffer depth
        uniform sampler2D u_materialTex;    // GBuffer material (metallic/roughness)
        uniform sampler3D u_vctVoxelTex;    // 3D 體素紋理

        uniform float u_vctWorldMinX;
        uniform float u_vctWorldMinY;
        uniform float u_vctWorldMinZ;
        uniform float u_vctVoxelSize;
        uniform float u_vctExtent;
        uniform int   u_vctResolution;
        uniform float u_vctConeAngle;
        uniform int   u_vctConeCount;
        uniform float u_vctMaxDistance;
        uniform float u_vctStepMultiplier;

        uniform mat4 u_invViewProj;
        uniform float u_giIntensity;

        out vec4 fragColor;

        // 世界座標 → 體素 UV
        vec3 worldToVoxelUV(vec3 worldPos) {
            return (worldPos - vec3(u_vctWorldMinX, u_vctWorldMinY, u_vctWorldMinZ))
                   / u_vctExtent;
        }

        // 從深度重建世界座標
        vec3 reconstructWorldPos(vec2 uv, float depth) {
            vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            vec4 world = u_invViewProj * clip;
            return world.xyz / world.w;
        }

        // 錐體追蹤（Crassin 2012 簡化版）
        vec4 coneTrace(vec3 origin, vec3 direction, float aperture) {
            vec4 accum = vec4(0.0);
            float dist = u_vctVoxelSize * 2.0; // 起始偏移避免自交

            for (int i = 0; i < 64 && dist < u_vctMaxDistance && accum.a < 0.95; i++) {
                vec3 samplePos = origin + direction * dist;
                vec3 uv = worldToVoxelUV(samplePos);

                if (any(lessThan(uv, vec3(0.0))) || any(greaterThan(uv, vec3(1.0)))) break;

                // 錐體半徑決定 mipmap 層級
                float coneRadius = dist * tan(aperture);
                float mipLevel = log2(max(1.0, coneRadius / u_vctVoxelSize));

                vec4 sample_ = textureLod(u_vctVoxelTex, uv, mipLevel);

                // 前到後合成
                float a = 1.0 - accum.a;
                accum.rgb += sample_.rgb * a;
                accum.a += sample_.a * a;

                // 步進（遠處步進更大，效能友好）
                dist += max(u_vctVoxelSize, coneRadius) * u_vctStepMultiplier;
            }
            return accum;
        }

        void main() {
            vec4 scene = texture(u_sceneTex, v_texCoord);
            float depth = texture(u_depthTex, v_texCoord).r;
            if (depth >= 1.0) { fragColor = scene; return; } // 天空

            vec3 normal = normalize(texture(u_normalTex, v_texCoord).xyz);
            vec3 worldPos = reconstructWorldPos(v_texCoord, depth);

            // 漫反射錐體：5 個方向（上半球均勻分佈）
            vec3 tangent = abs(normal.y) < 0.999
                ? normalize(cross(normal, vec3(0, 1, 0)))
                : normalize(cross(normal, vec3(1, 0, 0)));
            vec3 bitangent = cross(normal, tangent);

            vec4 indirectDiffuse = vec4(0.0);
            indirectDiffuse += coneTrace(worldPos, normal, u_vctConeAngle);
            indirectDiffuse += coneTrace(worldPos, normalize(normal + tangent), u_vctConeAngle * 1.2);
            indirectDiffuse += coneTrace(worldPos, normalize(normal - tangent), u_vctConeAngle * 1.2);
            indirectDiffuse += coneTrace(worldPos, normalize(normal + bitangent), u_vctConeAngle * 1.2);
            indirectDiffuse += coneTrace(worldPos, normalize(normal - bitangent), u_vctConeAngle * 1.2);
            indirectDiffuse /= 5.0;

            // 鏡面反射錐體（窄錐角）
            float roughness = texture(u_materialTex, v_texCoord).g;
            vec3 viewDir = normalize(-worldPos);
            vec3 reflectDir = reflect(-viewDir, normal);
            float specConeAngle = mix(0.02, u_vctConeAngle, roughness);
            vec4 indirectSpecular = coneTrace(worldPos, reflectDir, specConeAngle);

            float metallic = texture(u_materialTex, v_texCoord).r;
            vec3 gi = indirectDiffuse.rgb * (1.0 - metallic) + indirectSpecular.rgb * metallic;

            fragColor = vec4(scene.rgb + gi * u_giIntensity, scene.a);
        }
        """;

    // ── Hi-Z Downsample — 深度金字塔生成 ──
    private static final String HIZ_DOWNSAMPLE_FRAG = """
        #version 330 core
        in vec2 v_texCoord;

        uniform sampler2D u_depthTex;
        uniform vec2 u_texelSize; // 1.0 / previousMipSize

        out float fragDepth;

        void main() {
            // 取 2x2 區塊的最大深度（保守剔除）
            vec2 uv = v_texCoord;
            float d0 = texture(u_depthTex, uv + vec2(-0.5, -0.5) * u_texelSize).r;
            float d1 = texture(u_depthTex, uv + vec2( 0.5, -0.5) * u_texelSize).r;
            float d2 = texture(u_depthTex, uv + vec2(-0.5,  0.5) * u_texelSize).r;
            float d3 = texture(u_depthTex, uv + vec2( 0.5,  0.5) * u_texelSize).r;
            fragDepth = max(max(d0, d1), max(d2, d3));
        }
        """;
}
