package com.blockreality.api.client.render;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.render.rt.BRVulkanDevice;
import com.blockreality.api.client.render.rt.BRVulkanRT;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 運行時渲染設定 — 使用者可在遊戲中即時調整的渲染選項。
 *
 * 與 BRRenderConfig（編譯時常數）不同，BRRenderSettings 允許：
 *   1. 透過 /br_render 指令即時切換效果
 *   2. 設定自動存檔到 config/blockreality/render_settings.properties
 *   3. 預設風格：Cinema / Performance / Minimal / Custom
 *
 * 所有 getter 返回的值在渲染管線中被即時讀取（每幀檢查）。
 */
@OnlyIn(Dist.CLIENT)
public final class BRRenderSettings {
    private BRRenderSettings() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Settings");

    // ═══════════════════════════════════════════════════════
    //  預設風格
    // ═══════════════════════════════════════════════════════

    public enum RenderStyle {
        /** 電影級：所有效果最大化 */
        CINEMA("電影級", "所有效果最大化，最佳畫質"),
        /** 平衡：品質與效能兼顧 */
        BALANCED("平衡", "品質與效能兼顧"),
        /** 效能：關閉昂貴效果 */
        PERFORMANCE("效能", "關閉昂貴效果，最大幀率"),
        /** 最小：僅基礎後處理 */
        MINIMAL("最小", "僅基礎 Bloom + Tonemap"),
        /** 自訂：使用者自行調整 */
        CUSTOM("自訂", "手動控制每個效果");

        public final String displayName;
        public final String description;
        RenderStyle(String d, String desc) { displayName = d; description = desc; }
    }

    // ═══════════════════════════════════════════════════════
    //  運行時可調效果開關
    // ═══════════════════════════════════════════════════════

    private static RenderStyle currentStyle = RenderStyle.BALANCED;

    // 後處理效果
    private static boolean ssaoEnabled = true;
    private static boolean ssrEnabled = true;
    private static boolean ssgiEnabled = true;
    private static boolean taaEnabled = true;
    private static boolean bloomEnabled = true;
    private static boolean dofEnabled = false;
    private static boolean volumetricEnabled = true;
    private static boolean contactShadowEnabled = true;
    private static boolean motionBlurEnabled = false;

    // 環境效果
    private static boolean cloudEnabled = true;
    private static boolean weatherEnabled = true;
    private static boolean atmosphereEnabled = true;
    private static boolean waterEnabled = true;
    private static boolean fogEnabled = true;

    // 材質效果
    private static boolean sssEnabled = true;
    private static boolean anisotropicEnabled = true;
    private static boolean pomEnabled = true;

    // 進階渲染
    private static boolean vctEnabled = true;
    private static boolean computeSkinningEnabled = true;
    private static boolean gpuCullingEnabled = true;
    private static boolean meshShaderEnabled = true;

    // RT 效果
    private static boolean rtShadowsEnabled = true;
    private static boolean rtReflectionsEnabled = false;
    private static boolean rtAOEnabled = false;
    private static boolean rtGIEnabled = false;

    // 品質等級
    private static int shadowResolution = 2048;   // 512, 1024, 2048, 4096
    private static int ssaoSamples = 32;           // 8, 16, 32, 64
    private static float renderScale = 1.0f;       // 0.5, 0.75, 1.0, 1.5, 2.0

    private static Path settingsFile;
    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════

    public static void init(Path configDir) {
        settingsFile = configDir.resolve("blockreality").resolve("render_settings.properties");
        load();
        initialized = true;
        LOG.info("[Settings] 渲染設定載入完成 — 風格: {}, Tier: {}",
            currentStyle.displayName, BRRenderTier.getCurrentTier().name);
    }

    public static void cleanup() {
        save();
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  風格預設
    // ═══════════════════════════════════════════════════════

    /**
     * 套用渲染風格預設。
     */
    public static void applyStyle(RenderStyle style) {
        currentStyle = style;
        switch (style) {
            case CINEMA -> {
                ssaoEnabled = true; ssrEnabled = true; ssgiEnabled = true;
                taaEnabled = true; bloomEnabled = true; dofEnabled = true;
                volumetricEnabled = true; contactShadowEnabled = true; motionBlurEnabled = true;
                cloudEnabled = true; weatherEnabled = true; atmosphereEnabled = true;
                waterEnabled = true; fogEnabled = true;
                sssEnabled = true; anisotropicEnabled = true; pomEnabled = true;
                vctEnabled = true; computeSkinningEnabled = true;
                gpuCullingEnabled = true; meshShaderEnabled = true;
                rtShadowsEnabled = true; rtReflectionsEnabled = true;
                rtAOEnabled = true; rtGIEnabled = true;
                shadowResolution = 4096; ssaoSamples = 64; renderScale = 1.0f;
            }
            case BALANCED -> {
                ssaoEnabled = true; ssrEnabled = true; ssgiEnabled = true;
                taaEnabled = true; bloomEnabled = true; dofEnabled = false;
                volumetricEnabled = true; contactShadowEnabled = true; motionBlurEnabled = false;
                cloudEnabled = true; weatherEnabled = true; atmosphereEnabled = true;
                waterEnabled = true; fogEnabled = true;
                sssEnabled = true; anisotropicEnabled = true; pomEnabled = true;
                vctEnabled = true; computeSkinningEnabled = true;
                gpuCullingEnabled = true; meshShaderEnabled = true;
                rtShadowsEnabled = true; rtReflectionsEnabled = false;
                rtAOEnabled = false; rtGIEnabled = false;
                shadowResolution = 2048; ssaoSamples = 32; renderScale = 1.0f;
            }
            case PERFORMANCE -> {
                ssaoEnabled = true; ssrEnabled = false; ssgiEnabled = false;
                taaEnabled = true; bloomEnabled = true; dofEnabled = false;
                volumetricEnabled = false; contactShadowEnabled = false; motionBlurEnabled = false;
                cloudEnabled = false; weatherEnabled = true; atmosphereEnabled = true;
                waterEnabled = true; fogEnabled = true;
                sssEnabled = false; anisotropicEnabled = false; pomEnabled = false;
                vctEnabled = false; computeSkinningEnabled = true;
                gpuCullingEnabled = true; meshShaderEnabled = true;
                rtShadowsEnabled = false; rtReflectionsEnabled = false;
                rtAOEnabled = false; rtGIEnabled = false;
                shadowResolution = 1024; ssaoSamples = 16; renderScale = 1.0f;
            }
            case MINIMAL -> {
                ssaoEnabled = false; ssrEnabled = false; ssgiEnabled = false;
                taaEnabled = false; bloomEnabled = true; dofEnabled = false;
                volumetricEnabled = false; contactShadowEnabled = false; motionBlurEnabled = false;
                cloudEnabled = false; weatherEnabled = false; atmosphereEnabled = false;
                waterEnabled = false; fogEnabled = true;
                sssEnabled = false; anisotropicEnabled = false; pomEnabled = false;
                vctEnabled = false; computeSkinningEnabled = false;
                gpuCullingEnabled = false; meshShaderEnabled = false;
                rtShadowsEnabled = false; rtReflectionsEnabled = false;
                rtAOEnabled = false; rtGIEnabled = false;
                shadowResolution = 512; ssaoSamples = 8; renderScale = 0.75f;
            }
            case CUSTOM -> {
                // 不修改任何設定，保留使用者自訂值
            }
        }
        save();
        LOG.info("[Settings] 套用風格: {} ({})", style.displayName, style.description);
    }

    // ═══════════════════════════════════════════════════════
    //  個別效果開關
    // ═══════════════════════════════════════════════════════

    public static void setEffect(String effectName, boolean enabled) {
        currentStyle = RenderStyle.CUSTOM;
        switch (effectName.toLowerCase()) {
            case "ssao" -> ssaoEnabled = enabled;
            case "ssr" -> ssrEnabled = enabled;
            case "ssgi" -> ssgiEnabled = enabled;
            case "taa" -> taaEnabled = enabled;
            case "bloom" -> bloomEnabled = enabled;
            case "dof" -> dofEnabled = enabled;
            case "volumetric" -> volumetricEnabled = enabled;
            case "contact_shadow" -> contactShadowEnabled = enabled;
            case "motion_blur" -> motionBlurEnabled = enabled;
            case "cloud" -> cloudEnabled = enabled;
            case "weather" -> weatherEnabled = enabled;
            case "atmosphere" -> atmosphereEnabled = enabled;
            case "water" -> waterEnabled = enabled;
            case "fog" -> fogEnabled = enabled;
            case "sss" -> sssEnabled = enabled;
            case "anisotropic" -> anisotropicEnabled = enabled;
            case "pom" -> pomEnabled = enabled;
            case "vct" -> vctEnabled = enabled;
            case "compute_skinning" -> computeSkinningEnabled = enabled;
            case "gpu_culling" -> gpuCullingEnabled = enabled;
            case "mesh_shader" -> meshShaderEnabled = enabled;
            case "rt_shadow" -> rtShadowsEnabled = enabled;
            case "rt_reflection" -> rtReflectionsEnabled = enabled;
            case "rt_ao" -> rtAOEnabled = enabled;
            case "rt_gi" -> rtGIEnabled = enabled;
            default -> LOG.warn("[Settings] 未知效果: {}", effectName);
        }
        save();
    }

    public static boolean isEffectEnabled(String effectName) {
        return switch (effectName.toLowerCase()) {
            case "ssao" -> ssaoEnabled;
            case "ssr" -> ssrEnabled;
            case "ssgi" -> ssgiEnabled;
            case "taa" -> taaEnabled;
            case "bloom" -> bloomEnabled;
            case "dof" -> dofEnabled;
            case "volumetric" -> volumetricEnabled;
            case "contact_shadow" -> contactShadowEnabled;
            case "motion_blur" -> motionBlurEnabled;
            case "cloud" -> cloudEnabled;
            case "weather" -> weatherEnabled;
            case "atmosphere" -> atmosphereEnabled;
            case "water" -> waterEnabled;
            case "fog" -> fogEnabled;
            case "sss" -> sssEnabled;
            case "anisotropic" -> anisotropicEnabled;
            case "pom" -> pomEnabled;
            case "vct" -> vctEnabled;
            case "compute_skinning" -> computeSkinningEnabled;
            case "gpu_culling" -> gpuCullingEnabled;
            case "mesh_shader" -> meshShaderEnabled;
            case "rt_shadow" -> rtShadowsEnabled;
            case "rt_reflection" -> rtReflectionsEnabled;
            case "rt_ao" -> rtAOEnabled;
            case "rt_gi" -> rtGIEnabled;
            default -> false;
        };
    }

    /** 取得所有可用效果名稱 */
    public static List<String> getAllEffectNames() {
        return List.of(
            "ssao", "ssr", "ssgi", "taa", "bloom", "dof", "volumetric",
            "contact_shadow", "motion_blur", "cloud", "weather", "atmosphere",
            "water", "fog", "sss", "anisotropic", "pom", "vct",
            "compute_skinning", "gpu_culling", "mesh_shader",
            "rt_shadow", "rt_reflection", "rt_ao", "rt_gi"
        );
    }

    // ═══════════════════════════════════════════════════════
    //  Getters（管線每幀讀取）
    // ═══════════════════════════════════════════════════════

    public static RenderStyle getCurrentStyle() { return currentStyle; }
    public static boolean isSSAOEnabled() { return ssaoEnabled; }
    public static boolean isSSREnabled() { return ssrEnabled; }
    public static boolean isSSGIEnabled() { return ssgiEnabled; }
    public static boolean isTAAEnabled() { return taaEnabled; }
    public static boolean isBloomEnabled() { return bloomEnabled; }
    public static boolean isDOFEnabled() { return dofEnabled; }
    public static boolean isVolumetricEnabled() { return volumetricEnabled; }
    public static boolean isContactShadowEnabled() { return contactShadowEnabled; }
    public static boolean isMotionBlurEnabled() { return motionBlurEnabled; }
    public static boolean isCloudEnabled() { return cloudEnabled; }
    public static boolean isWeatherEnabled() { return weatherEnabled; }
    public static boolean isAtmosphereEnabled() { return atmosphereEnabled; }
    public static boolean isWaterEnabled() { return waterEnabled; }
    public static boolean isFogEnabled() { return fogEnabled; }
    public static boolean isSSSEnabled() { return sssEnabled; }
    public static boolean isAnisotropicEnabled() { return anisotropicEnabled; }
    public static boolean isPOMEnabled() { return pomEnabled; }
    public static boolean isVCTEnabled() { return vctEnabled; }
    public static boolean isComputeSkinningEnabled() { return computeSkinningEnabled; }
    public static boolean isGPUCullingEnabled() { return gpuCullingEnabled; }
    public static boolean isMeshShaderEnabled() { return meshShaderEnabled; }
    public static boolean isRTShadowsEnabled() { return rtShadowsEnabled; }
    public static boolean isRTReflectionsEnabled() { return rtReflectionsEnabled; }
    public static boolean isRTAOEnabled() { return rtAOEnabled; }
    public static boolean isRTGIEnabled() { return rtGIEnabled; }
    public static int getShadowResolution() { return shadowResolution; }
    public static int getSSAOSamples() { return ssaoSamples; }
    public static float getRenderScale() { return renderScale; }
    public static boolean isInitialized() { return initialized; }

    // ═══════════════════════════════════════════════════════
    //  設定值調整
    // ═══════════════════════════════════════════════════════

    public static void setShadowResolution(int res) {
        shadowResolution = Math.max(256, Math.min(8192, res));
        currentStyle = RenderStyle.CUSTOM;
        save();
    }

    public static void setSSAOSamples(int samples) {
        ssaoSamples = Math.max(4, Math.min(128, samples));
        currentStyle = RenderStyle.CUSTOM;
        save();
    }

    public static void setRenderScale(float scale) {
        renderScale = Math.max(0.25f, Math.min(4.0f, scale));
        currentStyle = RenderStyle.CUSTOM;
        save();
    }

    // ═══════════════════════════════════════════════════════
    //  持久化（properties 檔案）
    // ═══════════════════════════════════════════════════════

    private static void save() {
        if (settingsFile == null) return;
        try {
            Files.createDirectories(settingsFile.getParent());
            Properties props = new Properties();
            props.setProperty("style", currentStyle.name());
            for (String effect : getAllEffectNames()) {
                props.setProperty("effect." + effect, String.valueOf(isEffectEnabled(effect)));
            }
            props.setProperty("shadow_resolution", String.valueOf(shadowResolution));
            props.setProperty("ssao_samples", String.valueOf(ssaoSamples));
            props.setProperty("render_scale", String.valueOf(renderScale));
            props.store(Files.newOutputStream(settingsFile),
                "Block Reality Render Settings — 自動生成，可手動編輯");
        } catch (IOException e) {
            LOG.warn("[Settings] 儲存失敗: {}", e.getMessage());
        }
    }

    private static void load() {
        if (settingsFile == null || !Files.exists(settingsFile)) {
            applyStyle(RenderStyle.BALANCED);
            return;
        }
        try {
            Properties props = new Properties();
            props.load(Files.newInputStream(settingsFile));

            String styleName = props.getProperty("style", "BALANCED");
            try {
                currentStyle = RenderStyle.valueOf(styleName);
            } catch (IllegalArgumentException e) {
                currentStyle = RenderStyle.BALANCED;
            }

            // 如果是 CUSTOM，讀取個別效果設定
            if (currentStyle == RenderStyle.CUSTOM) {
                for (String effect : getAllEffectNames()) {
                    String val = props.getProperty("effect." + effect);
                    if (val != null) setEffectSilent(effect, Boolean.parseBoolean(val));
                }
            } else {
                applyStyle(currentStyle);
            }

            shadowResolution = Integer.parseInt(props.getProperty("shadow_resolution", "2048"));
            ssaoSamples = Integer.parseInt(props.getProperty("ssao_samples", "32"));
            renderScale = Float.parseFloat(props.getProperty("render_scale", "1.0"));
        } catch (Exception e) {
            LOG.warn("[Settings] 載入失敗，使用預設: {}", e.getMessage());
            applyStyle(RenderStyle.BALANCED);
        }
    }

    /** 靜默設定（不觸發 save 和 style 切換） */
    private static void setEffectSilent(String effectName, boolean enabled) {
        switch (effectName.toLowerCase()) {
            case "ssao" -> ssaoEnabled = enabled;
            case "ssr" -> ssrEnabled = enabled;
            case "ssgi" -> ssgiEnabled = enabled;
            case "taa" -> taaEnabled = enabled;
            case "bloom" -> bloomEnabled = enabled;
            case "dof" -> dofEnabled = enabled;
            case "volumetric" -> volumetricEnabled = enabled;
            case "contact_shadow" -> contactShadowEnabled = enabled;
            case "motion_blur" -> motionBlurEnabled = enabled;
            case "cloud" -> cloudEnabled = enabled;
            case "weather" -> weatherEnabled = enabled;
            case "atmosphere" -> atmosphereEnabled = enabled;
            case "water" -> waterEnabled = enabled;
            case "fog" -> fogEnabled = enabled;
            case "sss" -> sssEnabled = enabled;
            case "anisotropic" -> anisotropicEnabled = enabled;
            case "pom" -> pomEnabled = enabled;
            case "vct" -> vctEnabled = enabled;
            case "compute_skinning" -> computeSkinningEnabled = enabled;
            case "gpu_culling" -> gpuCullingEnabled = enabled;
            case "mesh_shader" -> meshShaderEnabled = enabled;
            case "rt_shadow" -> rtShadowsEnabled = enabled;
            case "rt_reflection" -> rtReflectionsEnabled = enabled;
            case "rt_ao" -> rtAOEnabled = enabled;
            case "rt_gi" -> rtGIEnabled = enabled;
        }
    }

    /**
     * 取得格式化的狀態摘要（供指令顯示）。
     */
    public static String getStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6[BR] §f渲染風格: §b").append(currentStyle.displayName)
          .append(" §7(").append(currentStyle.description).append(")\n");
        sb.append("§6[BR] §fTier: §b").append(BRRenderTier.getCurrentTier().name)
          .append(" §7(").append(BRRenderTier.getGPURenderer()).append(")\n");

        sb.append("§6[BR] §f後處理: ");
        appendEffect(sb, "SSAO", ssaoEnabled);
        appendEffect(sb, "SSR", ssrEnabled);
        appendEffect(sb, "SSGI", ssgiEnabled);
        appendEffect(sb, "TAA", taaEnabled);
        appendEffect(sb, "Bloom", bloomEnabled);
        appendEffect(sb, "DoF", dofEnabled);
        appendEffect(sb, "Vol", volumetricEnabled);
        sb.append("\n");

        sb.append("§6[BR] §f環境: ");
        appendEffect(sb, "Cloud", cloudEnabled);
        appendEffect(sb, "Weather", weatherEnabled);
        appendEffect(sb, "Water", waterEnabled);
        appendEffect(sb, "Fog", fogEnabled);
        appendEffect(sb, "Atmo", atmosphereEnabled);
        sb.append("\n");

        sb.append("§6[BR] §f材質: ");
        appendEffect(sb, "SSS", sssEnabled);
        appendEffect(sb, "Aniso", anisotropicEnabled);
        appendEffect(sb, "POM", pomEnabled);
        sb.append("\n");

        sb.append("§6[BR] §f進階: ");
        appendEffect(sb, "VCT", vctEnabled);
        appendEffect(sb, "ComputeSkin", computeSkinningEnabled);
        appendEffect(sb, "GPUCull", gpuCullingEnabled);
        appendEffect(sb, "MeshShader", meshShaderEnabled);
        sb.append("\n");

        if (BRVulkanDevice.isRTSupported()) {
            sb.append("§6[BR] §fRT: ");
            appendEffect(sb, "Shadow", rtShadowsEnabled);
            appendEffect(sb, "Reflect", rtReflectionsEnabled);
            appendEffect(sb, "AO", rtAOEnabled);
            appendEffect(sb, "GI", rtGIEnabled);
            sb.append("\n");
        }

        sb.append(String.format("§6[BR] §f品質: Shadow=%d SSAO=%d RenderScale=%.1fx",
            shadowResolution, ssaoSamples, renderScale));
        return sb.toString();
    }

    private static void appendEffect(StringBuilder sb, String name, boolean on) {
        sb.append(on ? "§a" : "§c").append(name).append(on ? "✓" : "✗").append(" ");
    }
}
