package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染開關 — 簡化版，只有「啟用」和「停用」兩個狀態。
 *
 * <p>原本的 TIER_0～TIER_3 分級系統已移除，改為單一 on/off 開關。
 * 舊的 Tier 常數（TIER_0～TIER_3）保留為向後相容靜態欄位，
 * 指向 ENABLED 或 DISABLED，讓舊程式碼無需修改即可編譯。
 */
@OnlyIn(Dist.CLIENT)
public final class BRRenderTier {

    private BRRenderTier() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Tier");

    // ═══ 狀態列舉 ═══

    public enum Tier {
        DISABLED("停用",  "N/A",    "N/A"),
        ENABLED ("啟用",  "GL 3.3+", "所有 GPU");

        public final String name, glRequirement, gpuTarget;
        Tier(String n, String g, String t) { name = n; glRequirement = g; gpuTarget = t; }

        // 向後相容靜態欄位 — 讓舊的 BRRenderTier.Tier.TIER_N 引用可編譯
        /** @deprecated 使用 {@link #DISABLED} */
        @Deprecated public static final Tier TIER_0 = DISABLED;
        /** @deprecated 使用 {@link #ENABLED} */
        @Deprecated public static final Tier TIER_1 = ENABLED;
        /** @deprecated 使用 {@link #ENABLED} */
        @Deprecated public static final Tier TIER_2 = ENABLED;
        /**
         * RT 層級相容欄位 — 開啟 RT 支援。
         */
        @Deprecated public static final Tier TIER_3 = ENABLED;
    }

    /**
     * 舊版 RT 細分 — 用於相容。
     */
    @Deprecated
    public enum RtSubTier { RT_ULTRA, RT_HIGH, RT_BALANCED }

    private static Tier currentTier = Tier.ENABLED;
    private static boolean initialized = false;
    private static String gpuVendor   = "unknown";
    private static String gpuRenderer = "unknown";

    // ═══ 生命週期 ═══

    /**
     * 初始化：讀取 GPU 資訊，預設啟用渲染。
     * 必須在 GL context 建立後於 render thread 呼叫。
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRRenderTier already initialized, skipping");
            return;
        }
        try {
            gpuVendor   = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            gpuRenderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            if (gpuVendor   == null) gpuVendor   = "unknown";
            if (gpuRenderer == null) gpuRenderer = "unknown";
        } catch (Exception e) {
            LOG.warn("Failed to read GPU info: {}", e.toString());
        }
        currentTier = Tier.ENABLED;
        initialized = true;
        LOG.info("GPU: {} ({})", gpuRenderer, gpuVendor);
        LOG.info("BRRenderTier initialized — 渲染: 啟用");
    }

    /** 重設所有狀態（資源重載或關閉時呼叫）。 */
    public static void cleanup() {
        currentTier = Tier.ENABLED;
        initialized = false;
        gpuVendor   = "unknown";
        gpuRenderer = "unknown";
        LOG.info("BRRenderTier cleaned up");
    }

    // ═══ 開關 ═══

    /** @return 渲染是否啟用 */
    public static boolean isEnabled() {
        return currentTier == Tier.ENABLED;
    }

    /** 設定渲染開關。 */
    public static void setEnabled(boolean on) {
        currentTier = on ? Tier.ENABLED : Tier.DISABLED;
        LOG.info("BRRenderTier — 渲染: {}", on ? "啟用" : "停用");
    }

    // ═══ 舊版 API（向後相容） ═══

    /** @return 目前狀態（{@link Tier#ENABLED} 或 {@link Tier#DISABLED}） */
    public static Tier getCurrentTier() { return currentTier; }

    /** @return 最高支援等級（永遠回傳 {@link Tier#ENABLED}） */
    public static Tier getMaxSupportedTier() { return Tier.ENABLED; }

    /**
     * 以舊版 Tier 值設定開關：非 DISABLED 即啟用。
     * @deprecated 請改用 {@link #setEnabled(boolean)}
     */
    @Deprecated
    public static void setTier(Tier tier) {
        setEnabled(tier != Tier.DISABLED);
    }

    /** @return 是否已初始化 */
    public static boolean isInitialized() { return initialized; }

    /**
     * 功能開關查詢。啟用時所有標準功能及 RT 功能皆可用。
     */
    public static boolean isFeatureEnabled(String feature) {
        if (!isEnabled()) return false;
        return switch (feature) {
            case "ray_tracing", "rt_omm_ser", "rt_high_bounces", "rt_gi" -> true;
            default -> true;
        };
    }

    /**
     * @return 回傳高畫質配置
     */
    @Deprecated
    public static RtSubTier getRtSubTier() { return RtSubTier.RT_HIGH; }

    // ═══ GPU 資訊 ═══

    public static String getGPUVendor()   { return gpuVendor; }
    public static String getGPURenderer() { return gpuRenderer; }
    public static String getGLVersion()   { return "N/A"; }

    public static boolean isNvidia() {
        return gpuVendor.toLowerCase().contains("nvidia");
    }
    public static boolean isAMD() {
        String v = gpuVendor.toLowerCase();
        return v.contains("amd") || v.contains("ati");
    }
    public static boolean isIntel() {
        return gpuVendor.toLowerCase().contains("intel");
    }
}
