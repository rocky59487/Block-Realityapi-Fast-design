package com.blockreality.api.client.render.pipeline;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.blockreality.api.client.render.optimization.BROptimizationEngine;
import com.blockreality.api.client.render.optimization.BRLODEngine;
import com.blockreality.api.client.render.optimization.BRMemoryOptimizer;
import com.blockreality.api.client.render.optimization.BRThreadedMeshBuilder;
import com.blockreality.api.client.render.animation.BRAnimationEngine;
import com.blockreality.api.client.render.effect.BREffectRenderer;
import com.blockreality.api.client.render.ui.BRRadialMenu;
import com.blockreality.api.client.render.ui.BRSelectionEngine;
import com.blockreality.api.client.render.ui.BRBlueprintPreview;
import com.blockreality.api.client.render.ui.BRQuickPlacer;
import com.blockreality.api.client.render.effect.BRAtmosphereEngine;
import com.blockreality.api.client.render.effect.BRWaterRenderer;
import com.blockreality.api.client.render.effect.BRParticleSystem;
import com.blockreality.api.client.render.effect.BRCloudRenderer;
import com.blockreality.api.client.render.shadow.BRCascadedShadowMap;
import com.blockreality.api.client.render.postfx.BRMotionBlurEngine;
import com.blockreality.api.client.render.postfx.BRColorGrading;
import com.blockreality.api.client.render.postfx.BRDebugOverlay;
import com.blockreality.api.client.render.postfx.BRGlobalIllumination;
import com.blockreality.api.client.render.effect.BRFogEngine;
import com.blockreality.api.client.render.effect.BRLensFlare;
import com.blockreality.api.client.render.effect.BRWeatherEngine;
import com.blockreality.api.client.render.postfx.BRSubsurfaceScattering;
import com.blockreality.api.client.render.postfx.BRAnisotropicReflection;
import com.blockreality.api.client.render.postfx.BRParallaxOcclusionMap;
import com.blockreality.api.client.render.optimization.BRShaderLOD;
import com.blockreality.api.client.render.optimization.BRAsyncComputeScheduler;
import com.blockreality.api.client.render.optimization.BROcclusionCuller;
import com.blockreality.api.client.render.optimization.BRGPUProfiler;
import com.blockreality.api.client.render.optimization.BRComputeSkinning;
import com.blockreality.api.client.render.optimization.BRMeshletEngine;
import com.blockreality.api.client.render.optimization.BRGPUCulling;
import com.blockreality.api.client.render.optimization.BRSparseVoxelDAG;
import com.blockreality.api.client.render.optimization.BRDiskLODCache;
import com.blockreality.api.client.render.optimization.BRMeshShaderPath;
import com.blockreality.api.client.render.optimization.BRPaletteCompressor;
import com.blockreality.api.client.render.postfx.BRAutoExposure;
import com.blockreality.api.client.render.BRRenderSettings;
import com.blockreality.api.client.render.rt.BRVulkanDevice;
import com.blockreality.api.client.render.rt.BRVulkanBVH;
import com.blockreality.api.client.render.rt.BRVulkanRT;
import com.blockreality.api.client.render.rt.BRVulkanInterop;
import com.blockreality.api.client.render.rt.BRSVGFDenoiser;
import com.blockreality.api.client.render.test.BRPipelineValidator;
import com.blockreality.api.client.render.test.BRMemoryLeakScanner;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Block Reality 固化渲染管線 — 頂級渲染引擎核心。
 *
 * 架構融合：
 *   - Iris/Radiance: 多 Pass 延遲渲染（Shadow → GBuffer → Deferred → Composite → Final）
 *   - Sodium/Embeddium: Greedy Meshing + Frustum Culling + VBO 批次
 *   - GeckoLib: 骨骼動畫插值系統
 *
 * 固化設計原則：
 *   1. 所有 shader 在啟動時編譯，執行時零分配
 *   2. 管線 pass 順序不可外部修改
 *   3. FBO 自動管理 resize
 *   4. 所有 uniform 通過 pre-computed 常數表更新
 *
 * 入口點：
 *   - {@link #init()} — 初始化（ClientSetup 呼叫一次）
 *   - {@link #onRenderLevel(RenderLevelStageEvent)} — 每幀渲染掛接
 *   - {@link #onResize(int, int)} — 視窗大小變更
 *   - {@link #shutdown()} — 關閉清除
 */
@OnlyIn(Dist.CLIENT)
@Deprecated(since = "Phase4", forRemoval = true)
public final class BRRenderPipeline {
    private BRRenderPipeline() {}

    private static boolean enabled = true;
    private static boolean initialized = false;
    private static long frameCount = 0;

    // ─── 子系統引用 ─────────────────────────────────────
    // 全部在 init() 中初始化，由管線統一調度

    // ═══════════════════════════════════════════════════════
    //  初始化 / 關閉
    // ═══════════════════════════════════════════════════════

    /**
     * 初始化整條渲染管線。
     * 必須在 GL context 與 Minecraft 實例皆可用後呼叫。
     */
    public static void init() {
        if (initialized) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        // 0. 運行時渲染設定（從 config 檔載入）
        BRRenderSettings.init(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());

        // 1. Framebuffer 系統
        BRFramebufferManager.init(w, h);

        // 2. 固化 Shader 編譯
        BRShaderEngine.init();

        // 3. 優化引擎（mesh cache、frustum、batcher）
        BROptimizationEngine.init();

        // 4. LOD 引擎（Distant Horizons + Voxy 風格，最大 1024 方塊視距）
        BRLODEngine.init();

        // 5. 動畫引擎
        BRAnimationEngine.init();

        // 6. 特效系統
        BREffectRenderer.init();

        // 7. 記憶體優化引擎（FerriteCore 風格）
        BRMemoryOptimizer.init();

        // 8. 多執行緒網格建構器（C2ME 風格）
        BRThreadedMeshBuilder.init();

        // 9. 多視角系統（Rhino / Axiom 風格）
        BRViewportManager.init(w, h);

        // 10. 輪盤 UI
        BRRadialMenu.init();

        // 11. 選取引擎（Axiom 風格）
        BRSelectionEngine.init();

        // 12. 藍圖預覽（幽靈方塊）
        BRBlueprintPreview.init();

        // 13. 快速放置器（SimpleBuilding 風格）
        BRQuickPlacer.init();

        // 14. 大氣渲染引擎（Rayleigh / Mie 散射天空）
        BRAtmosphereEngine.init();

        // 15. 水體渲染器（PBR 水面 — Gerstner 波浪 + 反射 + 焦散）
        BRWaterRenderer.init(w, h);

        // 16. 粒子系統（GPU Instanced 粒子）
        BRParticleSystem.init();

        // 17. 級聯陰影（CSM — 取代單層 Shadow Map）
        BRCascadedShadowMap.init();

        // 18. 體積雲渲染器（程序化 Ray March）
        BRCloudRenderer.init(w, h);

        // 19. Velocity Buffer（動態模糊基礎設施）
        BRMotionBlurEngine.init(w, h);

        // 20. 色彩分級（程序化 3D LUT）
        BRColorGrading.init();

        // 21. 除錯覆蓋層（效能 HUD + 渲染診斷）
        BRDebugOverlay.init();

        // 22. SSGI（螢幕空間全域光照 — 半解析度間接光）
        BRGlobalIllumination.init(w, h);

        // 23. 體積霧引擎（距離霧 + 高度霧 + 大氣散射）
        BRFogEngine.init();

        // 24. 鏡頭光暈（程序化太陽光暈效果）
        BRLensFlare.init();

        // 25. 天氣系統（雨/雪/暴風雨/極光 + 濕潤 PBR）
        BRWeatherEngine.init();

        // 26. 次表面散射（SSS — 樹葉/冰/蜂蜜散射效果）
        BRSubsurfaceScattering.init(w, h);

        // 27. 各向異性反射（金屬拉絲/結晶紋理反射）
        BRAnisotropicReflection.init();

        // 28. 視差遮蔽映射（POM — 磚縫/石紋深度感）
        BRParallaxOcclusionMap.init();

        // 29. Shader LOD（動態品質降級 — 幀率保護）
        BRShaderLOD.init();

        // 30. 非同步計算排程器（GL Fence Sync + PBO 非同步讀回）
        BRAsyncComputeScheduler.init(w, h);

        // 31. 遮蔽查詢剔除器（Hardware Occlusion Query）
        BROcclusionCuller.init();

        // 32. GPU Timeline Profiler（Timer Query 效能分析）
        BRGPUProfiler.init();

        // ── Phase 14: Research Report v4 未實現功能 ──

        // 33. GPU Compute Skinning（Wicked Engine 2017 — 50+ 實體自動啟用）
        BRComputeSkinning.init();

        // 34-38: 以下功能尚未整合到渲染迴圈（init 但從未 dispatch/使用），
        // 延遲到未來版本整合時再啟用，避免浪費 GPU 資源。
        // TODO: 完成整合後取消註解
        // BRMeshletEngine.init();
        // BRGPUCulling.init();
        // BRSparseVoxelDAG.init(BRRenderConfig.SVDAG_MAX_DEPTH);
        // BRDiskLODCache.init(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
        // BRMeshShaderPath.init();

        // 39. VCT Compute Shader（Voxel Cone Tracing 體素化 compute 管線）
        BRGlobalIllumination.initVCT();
        BRGlobalIllumination.initVCTCompute();

        // ── Phase 15: 報告剩餘功能 ──

        // 40. 渲染層級偵測（Tier 0/1/2/3 動態切換）
        BRRenderTier.init();

        // 41. Auto-Exposure Luminance Histogram（GPU compute 亮度直方圖）
        BRAutoExposure.init();

        // 42. Lithium-style Palette Compression（尚未整合到方塊狀態儲存）
        // TODO: 完成整合後取消註解
        // BRPaletteCompressor.init();

        // ── Phase 16: Vulkan Ray Tracing（Option B — 混合 GL+VK） ──

        if (BRRenderConfig.VULKAN_RT_ENABLED && BRRenderTier.isFeatureEnabled("ray_tracing")) {
            // 43. Vulkan Device（VkInstance + VkDevice + Queue）
            BRVulkanDevice.init();

            if (BRVulkanDevice.isRTSupported()) {
                // 44. GL/VK Interop（共享紋理）
                BRVulkanInterop.init(w, h);

                // 45. BVH 加速結構（BLAS/TLAS）
                BRVulkanBVH.init();

                // 46. RT Pipeline（Shadow/Reflection/AO/GI dispatch）
                BRVulkanRT.init();

                // 47. SVGF Denoiser（時空降噪）
                BRSVGFDenoiser.init(w, h);

                // 47.5. RT Compositor（整合 GBuffer + Denoiser + RT + 合成 quad）
                // 這是「LOD 相容 RT 系統」的主協調器：
                //   - VkAccelStructBuilder 接入 BRVoxelLODManager 作為 BLASUpdater
                //   - BRNRDDenoiser 整合 SVGF fallback
                //   - 全螢幕 composite pass
                try {
                    com.blockreality.api.client.rendering.BRRTCompositor.getInstance().init(w, h);
                    logInfo("BRRTCompositor 初始化完成 — RT GBuffer + Denoiser 就緒");
                } catch (Exception e) {
                    logInfo("BRRTCompositor 初始化失敗（非致命）: " + e.getMessage());
                }

                logInfo("Vulkan RT 初始化完成 — " + BRVulkanDevice.getDeviceName());
            } else {
                logInfo("Vulkan RT 不可用 — GPU 不支援 VK_KHR_ray_tracing_pipeline");
            }
        }

        initialized = true;
        logInfo("固化渲染管線初始化完成（Phase 16）— " + w + "x" + h +
            " — " + (BRVulkanDevice.isRTSupported() ? "47" : "42") + " 子系統" +
            " — Tier: " + BRRenderTier.getCurrentTier().name);

        // Phase 13: 初始化後自動驗證 + 記憶體基線
        try {
            BRMemoryLeakScanner.captureBaseline();
            java.util.List<BRPipelineValidator.ValidationResult> vr = BRPipelineValidator.runFullValidation();
            long failed = vr.stream().filter(r -> !r.passed()).count();
            if (failed > 0) {
                logInfo("⚠ 管線驗證：" + failed + "/" + vr.size() + " 項失敗 — 請執行 /br test all 查看詳情");
            } else {
                logInfo("管線驗證：" + vr.size() + "/" + vr.size() + " 全部通過");
            }
        } catch (Exception e) {
            logInfo("管線驗證跳過（非致命）: " + e.getMessage());
        }
    }

    /** 關閉管線，釋放所有 GL 資源 */
    public static void shutdown() {
        if (!initialized) return;
        // Phase 16 cleanup (Vulkan RT)
        if (BRVulkanDevice.isInitialized()) {
            BRSVGFDenoiser.cleanup();
            BRVulkanRT.cleanup();
            BRVulkanBVH.cleanup();
            BRVulkanInterop.cleanup();
            BRVulkanDevice.cleanup();
        }
        // Phase 15 cleanup
        // BRPaletteCompressor.cleanup();  // 未初始化，不需 cleanup
        BRAutoExposure.cleanup();
        BRRenderTier.cleanup();
        // Phase 14 cleanup
        BRGlobalIllumination.cleanupVCT();
        // 以下元件未初始化（延遲到整合完成），不需 cleanup
        // BRMeshShaderPath.cleanup();
        // BRDiskLODCache.cleanup();
        // BRSparseVoxelDAG.cleanup();
        // BRGPUCulling.cleanup();
        // BRMeshletEngine.cleanup();
        BRComputeSkinning.cleanup();
        // Original cleanup
        BRGPUProfiler.cleanup();
        BROcclusionCuller.cleanup();
        BRAsyncComputeScheduler.cleanup();
        BRShaderLOD.cleanup();
        BRParallaxOcclusionMap.cleanup();
        BRAnisotropicReflection.cleanup();
        BRSubsurfaceScattering.cleanup();
        BRWeatherEngine.cleanup();
        BRLensFlare.cleanup();
        BRFogEngine.cleanup();
        BRGlobalIllumination.cleanup();
        BRDebugOverlay.cleanup();
        BRColorGrading.cleanup();
        BRMotionBlurEngine.cleanup();
        BRCloudRenderer.cleanup();
        BRCascadedShadowMap.cleanup();
        BRParticleSystem.cleanup();
        BRWaterRenderer.cleanup();
        BRAtmosphereEngine.cleanup();
        BRQuickPlacer.cleanup();
        BRBlueprintPreview.cleanup();
        BRSelectionEngine.cleanup();
        BRRadialMenu.cleanup();
        BRViewportManager.cleanup();
        BRThreadedMeshBuilder.cleanup();
        BRMemoryOptimizer.cleanup();
        BREffectRenderer.cleanup();
        BRAnimationEngine.cleanup();
        BRLODEngine.cleanup();
        BROptimizationEngine.cleanup();
        BRShaderEngine.cleanup();
        BRFramebufferManager.cleanup();
        BRRenderSettings.cleanup();
        initialized = false;
        logInfo("固化渲染管線已關閉");
    }

    /** 視窗 resize 回調 */
    public static void onResize(int width, int height) {
        if (!initialized) return;
        BRFramebufferManager.resize(width, height);
        BRViewportManager.onResize(width, height);
        BRWaterRenderer.onResize(width, height);
        BRCloudRenderer.onResize(width, height);
        BRMotionBlurEngine.onResize(width, height);
        BRGlobalIllumination.onResize(width, height);
        BRSubsurfaceScattering.onResize(width, height);
        BRAsyncComputeScheduler.onResize(width, height);
        if (BRVulkanInterop.isInitialized()) BRVulkanInterop.onResize(width, height);
        if (BRSVGFDenoiser.isInitialized()) BRSVGFDenoiser.onResize(width, height);
    }

    // ═══════════════════════════════════════════════════════
    //  每幀渲染入口
    // ═══════════════════════════════════════════════════════

    /**
     * 主渲染入口 — 由 ClientSetup.ClientForgeEvents.onRenderLevel() 呼叫。
     *
     * ★ v4 spec Tier 0 架構（GL 3.3 相容）：
     *   本管線作為 Vanilla 渲染的「後處理疊加層」，而非場景替換。
     *   Vanilla 負責所有場景幾何渲染，BR 管線負責：
     *     1. 捕獲 Vanilla 已渲染的幀內容
     *     2. 對其施加後處理效果（Bloom、Tonemap、SSAO 等）
     *     3. 將結果寫回螢幕
     *     4. BR 專屬幾何（幽靈方塊、選框等）由獨立渲染器疊加
     *
     * 管線執行順序：
     *   Stage.AFTER_SOLID_BLOCKS → 子系統 tick/update（不修改畫面）
     *   Stage.AFTER_TRANSLUCENT_BLOCKS → 捕獲 Vanilla 幀 → 後處理鏈 → 寫回
     *   Stage.AFTER_LEVEL → Overlay UI + Effect
     */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!initialized || !enabled) return;

        RenderLevelStageEvent.Stage stage = event.getStage();

        // 構建 pass context
        Camera camera = event.getCamera();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick();
        Matrix4f projMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f viewMatrix = new Matrix4f(poseStack.last().pose());

        Minecraft mc = Minecraft.getInstance();
        float gameTime = mc.level != null ? mc.level.getGameTime() + partialTick : 0f;
        int worldTick = mc.level != null ? (int) mc.level.getGameTime() : 0;

        // ── AFTER_SOLID_BLOCKS: 子系統 tick/update（不渲染幾何）──
        if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            frameCount++;

            // 儲存當前幀的 viewProj 矩陣（TAA reprojection 用）
            projMatrix.mul(viewMatrix, currentViewProjMatrix);

            // GPU Profiler: 開始幀（讀回前一幀的 timer query 結果）
            BRGPUProfiler.beginFrame();

            // Occlusion Culler: 讀回前一幀的遮蔽查詢結果
            BROcclusionCuller.beginFrame();

            // LOD 引擎每幀更新（距離計算、LOD 層級重分配）
            Vec3 camPos = camera.getPosition();
            BRLODEngine.update(camPos.x, camPos.y, camPos.z, frameCount);

            // 大氣引擎：更新太陽位置和天空參數
            BRAtmosphereEngine.updateSunPosition(gameTime);

            // 水體動畫 tick
            BRWaterRenderer.tick(partialTick * 50.0f);

            // 雲層動畫 tick
            BRCloudRenderer.tick(partialTick * 50.0f);

            // 天氣系統 tick（雨/雪/暴風雨/極光 狀態機更新）
            if (BRRenderConfig.WEATHER_ENABLED && BRRenderSettings.isWeatherEnabled()) {
                float deltaSeconds = partialTick * 50.0f / 1000.0f;
                BRWeatherEngine.tick(deltaSeconds, gameTime, (float) camPos.y);
            }

            // ── Phase 14: 新系統 tick ──

            // GPU Compute Culling（Tier 1+ 功能）
            // NOTE: 目前 LOD section manager 尚未提供 AABB 資料，
            // 因此暫不設定 uniform / dispatch，避免每幀浪費 GPU 狀態切換。
            // 當 LOD section AABB 資料源就緒後，在此加入：
            //   BRGPUCulling.setViewProjMatrix(currentViewProjMatrix);
            //   BRGPUCulling.setFrustumPlanes(frustumPlanes);
            //   BRGPUCulling.setHiZTexture(hiZTex, hiZMips);
            //   BRGPUCulling.setScreenSize(sw, sh);
            //   BRGPUCulling.uploadAABBs(aabbData, objectCount);
            //   int visible = BRGPUCulling.dispatch(objectCount);
            // 前置條件：BRRenderConfig.GPU_CULLING_ENABLED
            //           && BRRenderSettings.isGPUCullingEnabled()
            //           && BRRenderTier.isFeatureEnabled("gpu_culling")
            //           && BRGPUCulling.isSupported()

            // Compute Skinning: Tier 1+ 功能，根據活躍實體數量自動切換
            if (BRRenderConfig.COMPUTE_SKINNING_ENABLED
                && BRRenderSettings.isComputeSkinningEnabled()
                && BRRenderTier.isFeatureEnabled("compute_skinning")) {
                BRAnimationEngine.evaluateComputeSkinning();
                if (BRAnimationEngine.isUsingComputeSkinning()) {
                    BRAnimationEngine.dispatchComputeSkinning();
                }
            }

            // VCT: Tier 1+ 功能，體素化場景（每 N 幀）
            if (BRRenderConfig.VCT_COMPUTE_ENABLED
                && BRRenderSettings.isVCTEnabled()
                && BRRenderTier.isFeatureEnabled("vct")
                && BRGlobalIllumination.isVCTInitialized()) {
                BRGlobalIllumination.voxelizeScene(
                    (float) camPos.x, (float) camPos.y, (float) camPos.z);
            }

            // Hi-Z 金字塔更新
            int gbufferDepth = BRFramebufferManager.getGbufferDepthTex();
            if (gbufferDepth > 0) {
                BRAsyncComputeScheduler.buildHiZPyramid(gbufferDepth);
            }
        }

        // ── AFTER_TRANSLUCENT_BLOCKS: 延遲管線 → 捕獲 Vanilla 幀 → 後處理鏈 ──
        else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            int screenW = mc.getWindow().getWidth();
            int screenH = mc.getWindow().getHeight();

            // ★ 步驟 0: 延遲渲染管線（Tier 1+ — Shadow → GBuffer → Deferred Lighting）
            // 在捕獲 Vanilla 幀之前，將 BR 結構幾何渲染到專用 FBO，
            // 這樣 deferred lighting 的結果可在 composite chain 中與 Vanilla 幀合成。
            if (BRRenderTier.getCurrentTier().ordinal() >= 1
                && BRFramebufferManager.isGbufferReady()) {

                // Shadow Pass — CSM 4 級聯深度渲染
                executeShadowPass(camera, partialTick, gameTime, worldTick, viewMatrix, projMatrix);

                // GBuffer Terrain Pass — 結構方塊寫入 5 個 GBuffer 附件
                RenderPassContext gbufCtx = new RenderPassContext(
                    RenderPass.GBUFFER_TERRAIN, poseStack, camera,
                    projMatrix, viewMatrix, partialTick, screenW * screenH
                ).withTime(gameTime, worldTick);
                executeGBufferPass(gbufCtx);

                // GBuffer Block Entity Pass — RBlockEntity 自訂幾何
                RenderPassContext beCtx = new RenderPassContext(
                    RenderPass.GBUFFER_BLOCK_ENTITIES, poseStack, camera,
                    projMatrix, viewMatrix, partialTick, screenW * screenH
                ).withTime(gameTime, worldTick);
                executeGBufferBlockEntityPass(beCtx);

                // Deferred Lighting Pass — 讀取 GBuffer + ShadowMap 解算光照
                executeDeferredLightingPass(camera, projMatrix, viewMatrix, gameTime, worldTick);
            }

            // ★ 步驟 1: 將 Vanilla 已渲染的 FBO 0 內容複製到 composite read buffer
            captureVanillaFrame(screenW, screenH);

            // ★ 步驟 1.5: Vulkan RT pass（Tier 3 — 混合 GL+VK）
            // 注意：RT 邏輯已搬移至 BRRTCompositor 並由 ForgeRenderEventBridge 於 AFTER_TRANSLUCENT_BLOCKS 觸發，
            // 此處之冗餘 traceRays 與 denoise 已移除，以避免重複渲染與覆蓋問題。

            // ★ 步驟 2: 後處理鏈（讀取捕獲的 Vanilla 幀，施加效果）
            executeCompositeChain(camera, gameTime);

            // ★ 步驟 3: 將後處理結果寫回 FBO 0（完整替換，因為是基於 Vanilla 幀處理的）
            blitPostProcessedFrame(screenW, screenH);
        }

        // ── AFTER_LEVEL: Overlay（不經延遲管線）──
        else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {

            RenderPassContext overlayCtx = new RenderPassContext(
                RenderPass.OVERLAY_UI, poseStack, camera,
                projMatrix, viewMatrix, partialTick, 0
            ).withTime(gameTime, worldTick);

            // 動畫 tick
            BRAnimationEngine.tick(partialTick);

            // 輪盤 UI tick（動畫更新）
            BRRadialMenu.tick(partialTick * 50.0f); // 約 deltaMs

            // 快速放置器 tick（更新幽靈預覽）
            if (BRQuickPlacer.getInstance() != null) {
                BRQuickPlacer.getInstance().tick();
            }

            // 粒子系統 tick + 渲染
            float deltaMs = partialTick * 50.0f;
            BRParticleSystem.tick(deltaMs);

            // 每幀結束重設物件池
            BRMemoryOptimizer.resetPools();

            // 消耗多執行緒建構完成的 LOD 網格（主執行緒 GPU 上傳）
            pollThreadedMeshResults();

            // UI 覆蓋層 + 特效
            BREffectRenderer.renderOverlays(overlayCtx);

            // Shader LOD: 記錄幀時間並自動調整品質等級
            if (BRRenderConfig.SHADER_LOD_ENABLED) {
                float frameTimeMs = BRDebugOverlay.getLastFrameTimeMs();
                BRShaderLOD.recordFrameTime(frameTimeMs);
            }

            // 節點圖評估（每幀 tick — 惰性評估僅處理 dirty 節點）
            if (BRRenderSettings.isNodeGraphActive()) {
                com.blockreality.api.node.EvaluateScheduler.tick();
                BRRenderSettings.syncFromNodeGraph();
            }

            // 非同步任務排程：每幀處理佇列中的���遲任務
            BRAsyncComputeScheduler.processTasks();

            // 除錯覆蓋層（F3+B 切換）
            BRDebugOverlay.recordFrameTime();
            Minecraft dbgMc = Minecraft.getInstance();
            BRDebugOverlay.render(dbgMc.getWindow().getWidth(), dbgMc.getWindow().getHeight());

            // GPU Profiler: 結束幀（切換 query buffer）
            BRGPUProfiler.endFrame();

            // ── Phase 14: Disk LOD cache 快取寫入 ──
            // （由 BRDiskLODCache 內部 ExecutorService 非同步處理）

            // TAA: 幀結束前更新 prevViewProjMatrix（下一幀 reprojection 用）
            prevViewProjMatrix.set(currentViewProjMatrix);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Pass 實作
    // ═══════════════════════════════════════════════════════

    /**
     * Shadow Pass — CSM 4 級聯深度渲染。
     * 取代舊版單層正交 shadow map，改用 BRCascadedShadowMap 引擎。
     *
     * 流程：
     *   1. 計算太陽方向 → BRCascadedShadowMap.setLightDirection()
     *   2. 計算對數/均勻混合分割距離
     *   3. tight-fit + texel snapping 計算 4 個光空間矩陣
     *   4. 各自渲染深度 → 4 個 CSM FBO（CASCADE_RESOLUTION: 2048/1536/1024/512）
     */
    private static void executeShadowPass(Camera camera, float partialTick,
                                           float gameTime, int worldTick,
                                           Matrix4f cameraView, Matrix4f cameraProj) {
        // ① 太陽方向（與 BRAtmosphereEngine 同源）
        float sunAngle = computeSunAngle(gameTime);
        float lx = -(float) Math.cos(sunAngle);
        float ly = -(float) Math.sin(sunAngle);
        float lz = 0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        BRCascadedShadowMap.setLightDirection(lx / len, ly / len, lz / len);

        // ② 計算分割距離（近 0.1，遠 SHADOW_MAX_DISTANCE，lambda=0.75 對數/均勻混合）
        BRCascadedShadowMap.computeSplitDistances(
            0.1f, BRRenderConfig.SHADOW_MAX_DISTANCE, 0.75f);

        // ③ 更新 4 個光空間矩陣（tight-fit + texel snapping 消除游泳）
        BRCascadedShadowMap.updateCascades(cameraView, cameraProj);

        // ④ 渲染 4 個級聯深度（各自 FBO + viewport + polygon offset）
        BRCascadedShadowMap.renderAllCascades();

        // ⑤ 恢復螢幕 viewport
        Minecraft mc = Minecraft.getInstance();
        GL30.glViewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
    }

    /**
     * GBuffer Pass — 將結構方塊幾何寫入 5 個 GBuffer 附件。
     * 借鑑 Iris gbuffers_terrain: position/normal/albedo/material/emission。
     */
    private static void executeGBufferPass(RenderPassContext ctx) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ctx.getFramebufferId());

        // 只在第一個 GBuffer pass 清除（terrain 是第一個）
        if (ctx.getPass() == RenderPass.GBUFFER_TERRAIN) {
            GL30.glClearColor(0, 0, 0, 0);
            GL30.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }

        BRShaderProgram gbufferShader = BRShaderEngine.getGBufferTerrainShader();
        if (gbufferShader != null) {
            gbufferShader.bind();
            uploadCommonUniforms(gbufferShader, ctx);

            // 透過優化引擎渲染（frustum culled + greedy meshed + batched）
            BROptimizationEngine.renderStructureGeometry(ctx);

            gbufferShader.unbind();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * GBuffer Block Entity Pass — RBlockEntity 自訂幾何（鑿刻方塊等）。
     */
    private static void executeGBufferBlockEntityPass(RenderPassContext ctx) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ctx.getFramebufferId());

        BRShaderProgram beShader = BRShaderEngine.getGBufferEntityShader();
        if (beShader != null) {
            beShader.bind();
            uploadCommonUniforms(beShader, ctx);

            // 動畫引擎：更新骨骼矩陣 → 優化引擎渲染
            BRAnimationEngine.uploadBoneMatrices(beShader);
            BROptimizationEngine.renderBlockEntityGeometry(ctx);

            beShader.unbind();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Translucent Pass — 幽靈方塊、選框半透明面。
     */
    private static void executeTranslucentPass(RenderPassContext ctx) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ctx.getFramebufferId());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        BRShaderProgram transShader = BRShaderEngine.getTranslucentShader();
        if (transShader != null) {
            transShader.bind();
            uploadCommonUniforms(transShader, ctx);

            BREffectRenderer.renderTranslucentGeometry(ctx);

            transShader.unbind();
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Deferred Lighting Pass — 讀取 GBuffer + ShadowMap 計算最終光照。
     * 全螢幕 quad pass，借鑑 Iris deferred pass。
     */
    private static void executeDeferredLightingPass(Camera camera, Matrix4f projMatrix,
                                                      Matrix4f viewMatrix,
                                                      float gameTime, int worldTick) {
        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);
        GL30.glClear(GL11.GL_COLOR_BUFFER_BIT);

        BRShaderProgram deferredShader = BRShaderEngine.getDeferredLightingShader();
        if (deferredShader != null) {
            deferredShader.bind();

            // 綁定 GBuffer 紋理到 texture unit 0~4
            for (int i = 0; i < BRRenderConfig.GBUFFER_ATTACHMENT_COUNT; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(i));
                deferredShader.setUniformInt("u_gbuffer" + i, i);
            }

            // 綁定 CSM 4 個 cascade shadow map 到 texture unit 5-8
            // bindShadowMaps() 同時上傳 u_csm[i]、u_lightViewProj[i]、u_cascadeSplit[i]、u_lightDir
            BRCascadedShadowMap.bindShadowMaps(deferredShader, 5);

            // 綁定 GBuffer 深度到 texture unit 9（CSM 佔用了 5-8）
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
            deferredShader.setUniformInt("u_depthTex", 9);

            // 深度線性化 / 世界座標重建所需的 uniform
            org.joml.Matrix4f invViewProj = new org.joml.Matrix4f(RenderSystem.getProjectionMatrix())
                    .mul(RenderSystem.getModelViewMatrix())
                    .invert();
            deferredShader.setUniformMatrix4f("u_invViewProj", invViewProj);
            deferredShader.setUniformFloat("u_nearPlane", 0.1f);
            deferredShader.setUniformFloat("u_farPlane",  BRRenderConfig.SHADOW_MAX_DISTANCE);
            deferredShader.setUniformFloat("u_shadowIntensity", BRRenderConfig.CSM_SHADOW_INTENSITY);

            // Uniform: 光照參數
            float sunAngle = computeSunAngle(gameTime);
            deferredShader.setUniformFloat("u_sunAngle", sunAngle);
            deferredShader.setUniformFloat("u_ambientStrength", BRRenderConfig.AO_STRENGTH);
            deferredShader.setUniformFloat("u_gameTime", gameTime);

            // 繪製全螢幕 quad
            renderFullScreenQuad();

            deferredShader.unbind();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        BRFramebufferManager.swapCompositeBuffers();
    }

    /**
     * Composite 鏈 — SSAO → Bloom → Tonemap。
     * 每個 pass 讀 main 寫 alt，完成後 swap。
     */
    private static void executeCompositeChain(Camera camera, float gameTime) {
        // Pass 1: SSAO / GTAO（環境遮蔽）
        if (BRRenderConfig.SSAO_ENABLED && BRRenderSettings.isSSAOEnabled()) {
            if (BRRenderConfig.GTAO_ENABLED) {
                // GTAO 模式 — 物理正確的 ground-truth AO
                // 參考 Jimenez 2016，使用水平切片 + 步進式射線 march
                executeGTAOPass(gameTime);
            } else {
                // 回退到基本 SSAO
                executeCompositePass(BRShaderEngine.getSSAOShader(), "SSAO", gameTime);
            }
        }

        // Pass 2: Contact Shadows（接觸陰影 — 疊加在 SSAO 之上）
        if (BRRenderConfig.CONTACT_SHADOW_ENABLED && BRRenderSettings.isContactShadowEnabled()) {
            executeCompositePass(BRShaderEngine.getContactShadowShader(), "ContactShadow", gameTime);
        }

        // Pass 3: POM（視差遮蔽映射 — 磚縫/石紋深度感，在 SSR 之前）
        if (BRRenderConfig.POM_ENABLED && BRRenderSettings.isPOMEnabled()) {
            BRParallaxOcclusionMap.render(gameTime);
        }

        // Pass 4: SSR（螢幕空間反射）
        if (BRRenderConfig.SSR_ENABLED && BRRenderSettings.isSSREnabled()) {
            executeCompositePass(BRShaderEngine.getSSRShader(), "SSR", gameTime);
        }

        // Pass 5: Anisotropic（各向異性反射 — SSR 之後修正金屬反射分佈）
        if (BRRenderConfig.ANISOTROPIC_ENABLED && BRRenderSettings.isAnisotropicEnabled()) {
            BRAnisotropicReflection.render(gameTime);
        }

        // Pass 6: Volumetric Lighting（體積光 / God Rays）
        if (BRRenderConfig.VOLUMETRIC_ENABLED && BRRenderSettings.isVolumetricEnabled()) {
            executeCompositePass(BRShaderEngine.getVolumetricShader(), "Volumetric", gameTime);
        }

        // Pass 5: 體積雲（在 bloom 之前，與 HDR 色彩合成）
        if (BRRenderConfig.CLOUD_ENABLED && BRRenderSettings.isCloudEnabled()) {
            // 雲層 ray march 到低解析度 FBO（使用當前 projection + view 矩陣）
            Matrix4f cloudProj = new Matrix4f(RenderSystem.getProjectionMatrix());
            Matrix4f cloudView = new Matrix4f().rotation(camera.rotation());
            Matrix4f projView = new Matrix4f();
            cloudProj.mul(cloudView, projView);
            Matrix4f invProjView = new Matrix4f(projView).invert();
            org.joml.Vector3f camPos3f = new org.joml.Vector3f(
                (float) camera.getPosition().x,
                (float) camera.getPosition().y,
                (float) camera.getPosition().z
            );
            BRCloudRenderer.renderClouds(invProjView, camPos3f,
                BRAtmosphereEngine.getSunDirection(),
                BRAtmosphereEngine.getSunColor(),
                gameTime);
            // 上採樣合成到主 composite buffer
            Minecraft mc = Minecraft.getInstance();
            BRCloudRenderer.compositeToScreen(
                BRFramebufferManager.getCompositeWriteFbo(),
                mc.getWindow().getWidth(), mc.getWindow().getHeight());
            BRFramebufferManager.swapCompositeBuffers();
        }

        // Pass 6: 體積霧（距離霧 + 高度霧 + inscattering）
        if (BRRenderConfig.FOG_ENABLED && BRRenderSettings.isFogEnabled()) {
            Vec3 fogCamPos = camera.getPosition();
            org.joml.Vector3f fogSunDir = BRAtmosphereEngine.getSunDirection();
            BRFogEngine.updateFogColor(BRAtmosphereEngine.getSunColor()); // 霧色跟隨大氣
            BRFogEngine.renderFogPass((float) fogCamPos.y, fogSunDir, gameTime);
        }

        // Pass 7: Weather（天氣粒子 — 雨/雪/閃電/極光）
        if (BRRenderConfig.WEATHER_ENABLED && BRRenderSettings.isWeatherEnabled()) {
            Vec3 weatherCamPos = camera.getPosition();
            BRWeatherEngine.render(gameTime, (float) weatherCamPos.y);
        }

        // Pass 8: Wet PBR（濕潤/積雪表面修正）
        if (BRRenderConfig.WEATHER_ENABLED && BRRenderSettings.isWeatherEnabled()) {
            float wetness = BRWeatherEngine.getGlobalWetness();
            float snow = BRWeatherEngine.getSnowCoverage();
            if (wetness > 0.001f || snow > 0.001f) {
                executeCompositePass(BRShaderEngine.getWetPbrShader(), "WetPBR", gameTime);
            }
        }

        // Pass 9: Bloom
        executeCompositePass(BRShaderEngine.getBloomShader(), "Bloom", gameTime);

        // Pass 10: Auto-Exposure + Tonemap
        if (BRRenderConfig.AUTO_EXPOSURE_ENABLED) {
            updateAutoExposure(gameTime);
        }
        executeCompositePass(BRShaderEngine.getTonemapShader(), "Tonemap", gameTime);

        // Pass 11: DoF（景深 — Tonemap 之後以 LDR 操作避免亮點問題）
        if (BRRenderConfig.DOF_ENABLED && BRRenderSettings.isDOFEnabled()) {
            executeCompositePass(BRShaderEngine.getDOFShader(), "DOF", gameTime);
        }

        // Pass 12: Cinematic Post-FX（暈影 + 色差 + 動態模糊 + 底片顆粒）
        if (BRRenderConfig.CINEMATIC_ENABLED) {
            executeCompositePass(BRShaderEngine.getCinematicShader(), "Cinematic", gameTime);
        }

        // Pass 13: Color Grading（3D LUT 色彩分級 + 時段色偏）
        if (BRRenderConfig.COLOR_GRADING_ENABLED) {
            BRColorGrading.tick();
            executeCompositePass(BRShaderEngine.getColorGradeShader(), "ColorGrade", gameTime);
        }

        // Pass 14: Lens Flare（鏡頭光暈 — 太陽光源程序化折射效果）
        if (BRRenderConfig.LENS_FLARE_ENABLED) {
            org.joml.Vector3f lfSunDir = BRAtmosphereEngine.getSunDirection();
            Matrix4f viewProj = new Matrix4f();
            new Matrix4f(RenderSystem.getProjectionMatrix())
                .mul(new Matrix4f().rotation(camera.rotation()), viewProj);
            BRLensFlare.updateSunPosition(lfSunDir, viewProj);
            BRLensFlare.updateOcclusion();
            BRLensFlare.render(gameTime);
        }

        // Pass 15: TAA（時序抗鋸齒 — 最後一個全螢幕 pass）
        if (BRRenderConfig.TAA_ENABLED && BRRenderSettings.isTAAEnabled()) {
            executeTAAPass(gameTime);
        }
    }

    /** 上一幀的 view-projection 矩陣（TAA 重投影用） */
    private static Matrix4f prevViewProjMatrix = new Matrix4f();
    private static Matrix4f currentViewProjMatrix = new Matrix4f();

    /**
     * TAA Pass — 時序抗鋸齒。
     * 結合當前幀與歷史幀，使用鄰域鉗制防止 ghosting。
     * 參考 Karis 2014 "High Quality Temporal Supersampling"。
     *
     * 修正項目（v4 spec 審核）：
     *   1. invViewProj 正確計算（從當前 viewProj 求逆）
     *   2. 運動向量綁定 BRMotionBlurEngine 生成的真實 velocity texture
     *   3. 歷史幀使用 TAA 專用歷史 buffer 而非 GBuffer attachment 0
     *   4. Halton jitter 透過專用 uniform 傳遞（非借用 u_gameTime）
     *   5. TAA 完成後將當前結果複製到歷史 buffer 供下一幀使用
     */
    private static void executeTAAPass(float gameTime) {
        BRShaderProgram taaShader = BRShaderEngine.getTAAShader();
        if (taaShader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);
        GL30.glClear(GL11.GL_COLOR_BUFFER_BIT);

        taaShader.bind();

        // 當前幀顏色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        taaShader.setUniformInt("u_currentTex", 0);

        // 歷史幀 — 使用 TAA 專用歷史 buffer
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getTaaHistoryTex());
        taaShader.setUniformInt("u_historyTex", 1);

        // 深度
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        taaShader.setUniformInt("u_depthTex", 2);

        // 運動向量 — 綁定 BRMotionBlurEngine 生成的真實 velocity texture
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRMotionBlurEngine.getVelocityTexture());
        taaShader.setUniformInt("u_motionTex", 3);

        // 矩陣 — 正確計算 invViewProj
        taaShader.setUniformMat4("u_prevViewProj", prevViewProjMatrix);
        Matrix4f invViewProj = new Matrix4f(currentViewProjMatrix).invert();
        taaShader.setUniformMat4("u_invViewProj", invViewProj);

        // 螢幕尺寸
        float screenW = BRFramebufferManager.getScreenWidth();
        float screenH = BRFramebufferManager.getScreenHeight();
        taaShader.setUniformFloat("u_screenWidth", screenW);
        taaShader.setUniformFloat("u_screenHeight", screenH);

        // Halton 序列 sub-pixel 抖動 — 透過專用 uniform 傳遞
        int jitterIndex = (int)(frameCount % BRRenderConfig.TAA_JITTER_SAMPLES);
        float jitterX = halton(jitterIndex, 2) - 0.5f;
        float jitterY = halton(jitterIndex, 3) - 0.5f;
        taaShader.setUniformVec2("u_jitterOffset", jitterX / screenW, jitterY / screenH);
        taaShader.setUniformFloat("u_blendFactor", BRRenderConfig.TAA_BLEND_FACTOR);

        renderFullScreenQuad();

        taaShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // ★ 修復 RP-1：先複製 TAA 輸出到歷史 buffer，再 swap。
        //   TAA 結果寫在 compositeWrite buffer 中。swap 後它才變成 read。
        //   但 copyToTaaHistory 需要的是 TAA 的「輸出」，因此必須在 swap 前
        //   用 write buffer 的 texture（即 TAA 剛寫完的那張）。
        //   原始程式碼先 swap 再 copy read → 拿到的是 TAA 的「輸入」= 錯誤。
        int taaOutputTex = BRFramebufferManager.getCompositeWriteTex();
        BRFramebufferManager.copyToTaaHistory(taaOutputTex);

        BRFramebufferManager.swapCompositeBuffers();
    }

    /**
     * Halton 低差異序列 — 用於 TAA sub-pixel 抖動。
     * 比 random jitter 更均勻，減少temporal noise。
     */
    private static float halton(int index, int base) {
        float result = 0.0f;
        float f = 1.0f / base;
        int i = index;
        while (i > 0) {
            result += f * (i % base);
            i = i / base;
            f = f / base;
        }
        return result;
    }

    private static void executeCompositePass(BRShaderProgram shader, String name, float gameTime) {
        if (shader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);
        GL30.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_inputTex", 0);

        // SSAO 需要深度和法線
        if ("SSAO".equals(name)) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
            shader.setUniformInt("u_depthTex", 1);

            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(1)); // normal
            shader.setUniformInt("u_normalTex", 2);

            shader.setUniformInt("u_kernelSize", BRRenderConfig.SSAO_KERNEL_SIZE);
            shader.setUniformFloat("u_radius", BRRenderConfig.SSAO_RADIUS);
        }

        // Bloom 參數
        if ("Bloom".equals(name)) {
            shader.setUniformFloat("u_threshold", BRRenderConfig.BLOOM_THRESHOLD);
            shader.setUniformFloat("u_intensity", BRRenderConfig.BLOOM_INTENSITY);
        }

        // Tonemap 模式
        if ("Tonemap".equals(name)) {
            shader.setUniformInt("u_tonemapMode", BRRenderConfig.TONEMAP_MODE);
        }

        // Color Grading — 綁定 3D LUT 紋理 + 時段因子 uniforms
        // COLOR_GRADE_FRAG shader 使用 u_mainTex 而非 u_inputTex
        if ("ColorGrade".equals(name)) {
            shader.setUniformInt("u_mainTex", 0);
            com.blockreality.api.client.render.postfx.BRColorGrading.bindLUT(shader, 5);
            shader.setUniformFloat("u_intensity", BRRenderConfig.COLOR_GRADING_INTENSITY);
        }

        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_screenWidth", BRFramebufferManager.getScreenWidth());
        shader.setUniformFloat("u_screenHeight", BRFramebufferManager.getScreenHeight());

        renderFullScreenQuad();

        shader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        BRFramebufferManager.swapCompositeBuffers();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GTAO（Ground Truth Ambient Occlusion）
    //  參考 Jimenez 2016: "Practical Real-Time Strategies for
    //  Accurate Indirect Occlusion"
    // ═══════════════════════════════════════════════════════════════

    /**
     * 執行 GTAO pass — 取代基本 SSAO，提供物理正確的環境遮蔽。
     *
     * GTAO 演算法：
     *   1. 對每個螢幕像素，在水平方向取 N 個切片（GTAO_SLICES）
     *   2. 每個切片沿視線方向步進（GTAO_STEPS_PER_SLICE）
     *   3. 用 horizon angle 計算可見性積分
     *   4. 結果為 [0,1] 的遮蔽值
     *
     * 相比基本 SSAO（kernel sampling），GTAO 具有：
     *   - 更準確的物理模型（基於可見性積分而非距離採樣）
     *   - 更少的 banding artifacts
     *   - 與 screen-space 法線更一致的結果
     */
    private static void executeGTAOPass(float gameTime) {
        BRShaderProgram gtaoShader = BRShaderEngine.getSSAOShader(); // 共用 SSAO shader slot
        if (gtaoShader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);
        GL30.glClear(GL11.GL_COLOR_BUFFER_BIT);

        gtaoShader.bind();

        // 輸入紋理
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        gtaoShader.setUniformInt("u_inputTex", 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        gtaoShader.setUniformInt("u_depthTex", 1);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(1));
        gtaoShader.setUniformInt("u_normalTex", 2);

        // GTAO 特有 uniforms
        gtaoShader.setUniformInt("u_gtaoSlices", BRRenderConfig.GTAO_SLICES);
        gtaoShader.setUniformInt("u_gtaoStepsPerSlice", BRRenderConfig.GTAO_STEPS_PER_SLICE);
        gtaoShader.setUniformFloat("u_gtaoRadius", BRRenderConfig.GTAO_RADIUS);
        gtaoShader.setUniformFloat("u_gtaoFalloff", BRRenderConfig.GTAO_FALLOFF_EXPONENT);
        gtaoShader.setUniformInt("u_gtaoEnabled", 1); // 切換 shader 內部路徑

        // 通用 uniforms
        gtaoShader.setUniformFloat("u_screenWidth", BRFramebufferManager.getScreenWidth());
        gtaoShader.setUniformFloat("u_screenHeight", BRFramebufferManager.getScreenHeight());
        gtaoShader.setUniformFloat("u_gameTime", gameTime);

        renderFullScreenQuad();

        gtaoShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        BRFramebufferManager.swapCompositeBuffers();
    }

    // ═══════════════════════════════════════════════════════════════
    //  自動曝光（Luminance Histogram）
    //  參考 Radiance: auto-exposure via luminance histogram
    // ═══════════════════════════════════════════════════════════════

    /** 當前曝光值（EV） */
    private static float currentExposureEV = 0.0f;

    /** 上一幀的平均亮度 */
    private static float prevAvgLuminance = 0.18f;

    /**
     * 更新自動曝光 — 基於亮度直方圖的自適應曝光。
     *
     * 演算法：
     *   1. 讀取上一幀的平均亮度（透過 1×1 mipmap 或 PBO 回讀）
     *   2. 截斷直方圖的低/高百分位（避免極端值主導）
     *   3. 計算目標曝光值：EV = log2(avgLuminance / 0.18) 的反向
     *   4. 平滑適應：lerp(currentEV, targetEV, adaptSpeed * deltaTime)
     *   5. 將曝光值傳遞給 tonemap shader 的 u_exposure uniform
     */
    private static void updateAutoExposure(float gameTime) {
        // Phase 15: 使用 GPU compute histogram 計算平均亮度（如可用）
        if (BRAutoExposure.isInitialized()) {
            int sceneTex = BRFramebufferManager.getCompositeReadTex();
            int sw = BRFramebufferManager.getScreenWidth();
            int sh = BRFramebufferManager.getScreenHeight();
            BRAutoExposure.compute(sceneTex, sw, sh);
            prevAvgLuminance = BRAutoExposure.getAverageLuminance();
        }

        float targetLuminance = prevAvgLuminance;

        // 防止除以零和極端值
        targetLuminance = Math.max(0.001f, Math.min(targetLuminance, 100.0f));

        // 目標曝光值：中灰 (0.18) 映射到 targetLuminance
        float targetEV = (float) (Math.log(targetLuminance / 0.18) / Math.log(2));
        targetEV = Math.max(BRRenderConfig.AUTO_EXPOSURE_MIN_EV,
                   Math.min(BRRenderConfig.AUTO_EXPOSURE_MAX_EV, targetEV));

        // 平滑適應
        float adaptRate = 1.0f - (float) Math.exp(-gameTime / BRRenderConfig.AUTO_EXPOSURE_ADAPT_SPEED);
        adaptRate = Math.max(0.01f, Math.min(1.0f, adaptRate));
        currentExposureEV += (targetEV - currentExposureEV) * adaptRate * 0.016f; // ~60fps

        // 計算線性曝光乘數
        float exposureMultiplier = (float) Math.pow(2.0, -currentExposureEV);

        // 設定給 tonemap shader（在下一個 executeCompositePass 中生效）
        BRShaderProgram tonemapShader = BRShaderEngine.getTonemapShader();
        if (tonemapShader != null) {
            tonemapShader.bind();
            tonemapShader.setUniformFloat("u_exposure", exposureMultiplier);
            tonemapShader.setUniformFloat("u_avgLuminance", targetLuminance);
            tonemapShader.unbind();
        }
    }

    /** 取得當前曝光值（EV） — 供 HUD 或除錯覆蓋顯示 */
    public static float getCurrentExposureEV() { return currentExposureEV; }

    /** 設定上一幀平均亮度（由 PBO 回讀或降採樣結果呼叫） */
    public static void setMeasuredLuminance(float luminance) {
        prevAvgLuminance = luminance;
    }

    /**
     * ★ v4 Tier 0: 捕獲 Vanilla 已渲染的幀到 composite buffer。
     * 使用 glBlitFramebuffer 從 FBO 0 複製到 composite write FBO，
     * 然後 swap 使其成為 read buffer，供後處理鏈讀取。
     */
    private static void captureVanillaFrame(int screenW, int screenH) {
        // 取得 Minecraft 主 FBO（可能是 0 或 Optifine/Iris 的自訂 FBO）
        int[] currentFbo = new int[1];
        GL30.glGetIntegerv(GL30.GL_FRAMEBUFFER_BINDING, currentFbo);
        int vanillaFbo = currentFbo[0];

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();

        // Blit vanilla frame → composite write buffer
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, vanillaFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, writeFbo);
        GL30.glBlitFramebuffer(
            0, 0, screenW, screenH,
            0, 0, screenW, screenH,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
        );

        // 恢復綁定並 swap，使捕獲的幀成為 read buffer
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, vanillaFbo);
        BRFramebufferManager.swapCompositeBuffers();
    }

    /**
     * ★ v4 Tier 0: 將後處理完成的幀寫回 FBO 0。
     * 因為後處理是基於 Vanilla 原始幀做的，所以直接覆蓋即可（非疊加）。
     */
    private static void blitPostProcessedFrame(int screenW, int screenH) {
        int readTex = BRFramebufferManager.getCompositeReadTex();

        // 取得當前 FBO（可能被 Vanilla 切換過）
        int[] currentFbo = new int[1];
        GL30.glGetIntegerv(GL30.GL_FRAMEBUFFER_BINDING, currentFbo);
        int targetFbo = currentFbo[0];

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
        GL30.glViewport(0, 0, screenW, screenH);

        // 不需要混合 — 後處理結果已包含完整的 Vanilla 場景
        RenderSystem.disableBlend();

        BRShaderProgram fs = BRShaderEngine.getFinalShader();
        if (fs != null) {
            fs.bind();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
            fs.setUniformInt("u_sceneTex", 0);

            // ★ v4: u_depthTex 不再需要（不判斷 BR 幾何像素，全幀後處理）
            //   如果 final shader 中有 discard(depth==1.0) 邏輯，會跳過所有像素。
            //   這裡綁定 composite read tex 本身作為 dummy，shader 應忽略 depth。
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
            fs.setUniformInt("u_depthTex", 1);

            renderFullScreenQuad();
            fs.unbind();
        } else {
            // ★ 降級路徑：finalShader 編譯失敗時，使用 glBlitFramebuffer 將後處理結果寫回。
            //   這確保即使 final shader 不可用，已成功執行的後處理 pass 仍能顯示在螢幕上。
            int readFbo = BRFramebufferManager.getCompositeReadFbo();
            if (readFbo > 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFbo);
                GL30.glBlitFramebuffer(
                    0, 0, screenW, screenH,
                    0, 0, screenW, screenH,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
                );
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            }
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    // ═══════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════

    /**
     * 繪製全螢幕 quad — 後處理 pass 共用。
     * 使用無 VBO 的 gl_VertexID trick（OpenGL 3.3+）。
     */
    private static void renderFullScreenQuad() {
        GL30.glBindVertexArray(getEmptyVao());
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    private static int emptyVao = 0;
    private static int getEmptyVao() {
        if (emptyVao == 0) {
            emptyVao = GL30.glGenVertexArrays();
        }
        return emptyVao;
    }

    /** 上傳通用 uniform（每個 GBuffer pass 共用） */
    private static void uploadCommonUniforms(BRShaderProgram shader, RenderPassContext ctx) {
        shader.setUniformMat4("u_projMatrix", ctx.getProjectionMatrix());
        shader.setUniformMat4("u_viewMatrix", ctx.getViewMatrix());
        shader.setUniformFloat("u_gameTime", ctx.getGameTime());
        shader.setUniformFloat("u_partialTick", ctx.getPartialTick());
        shader.setUniformVec3("u_cameraPos",
            (float) ctx.getCamX(), (float) ctx.getCamY(), (float) ctx.getCamZ());
    }

    /**
     * 消耗多執行緒建構完成的 LOD 網格結果。
     * 在主執行緒中上傳到 GPU（OpenGL 指令只能在 GL context 執行緒發出）。
     * 每幀最多處理 MAX_GPU_UPLOAD_PER_FRAME bytes 以防止卡頓。
     */
    private static void pollThreadedMeshResults() {
        long uploadedBytes = 0;
        BRThreadedMeshBuilder.MeshBuildResult result;
        while ((result = BRThreadedMeshBuilder.pollResult()) != null) {
            if (result.getVertices() != null && result.getVertices().length > 0) {
                long meshBytes = (long) result.getVertices().length * 4 + (long) result.getIndices().length * 4;
                uploadedBytes += meshBytes;

                // 通知 LOD 引擎更新該段落的 GPU 資源
                BRLODEngine.markDirty(result.getSectionX(), result.getSectionZ());
            }
            if (uploadedBytes >= BRRenderConfig.MAX_GPU_UPLOAD_PER_FRAME) {
                break; // 超過每幀上傳預算，餘下留到下幀
            }
        }
    }

    /** 計算太陽角度（0~2π，同 Iris sunAngle） */
    private static float computeSunAngle(float gameTime) {
        // Minecraft: 6000 tick = 正午，24000 tick = 一天
        float dayProgress = (gameTime % 24000f) / 24000f;
        return dayProgress * (float) (Math.PI * 2.0);
    }

    // ─── 狀態 ───────────────────────────────────────────

    public static void setEnabled(boolean flag) { enabled = flag; }
    public static boolean isEnabled() { return enabled; }
    public static boolean isInitialized() { return initialized; }
    public static long getFrameCount() { return frameCount; }

    private static void logInfo(String msg) {
        org.slf4j.LoggerFactory.getLogger("BR-Pipeline").info(msg);
    }
}
