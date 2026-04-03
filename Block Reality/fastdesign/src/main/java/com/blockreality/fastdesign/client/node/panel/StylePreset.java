package com.blockreality.fastdesign.client.node.panel;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代表一個完整的光影「風格預設」，包含名稱、描述、縮圖資源、效能評級，
 * 以及一組節點圖參數覆蓋值（portId → value）。
 *
 * <p>StylePreset 本身是不可變資料物件；實際套用邏輯由 {@link StylePresetRegistry}
 * 和 {@link BidirectionalSync} 負責。
 */
@OnlyIn(Dist.CLIENT)
public final class StylePreset {

    // -------------------------------------------------------------------------
    // 效能評級
    // -------------------------------------------------------------------------

    /** 效能評級，用於在 UI 卡片上顯示效能損耗標籤。 */
    public enum PerformanceTier {
        /** 極低消耗，任何顯卡皆可流暢運行 */
        LITE("輕量", 0xFF55FF55),
        /** 中等消耗，建議 GTX 1060 / RX 580 以上 */
        BALANCED("均衡", 0xFF55AAFF),
        /** 高消耗，建議 RTX 3070 / RX 6700 XT 以上 */
        DEMANDING("高效能", 0xFFFFAA00),
        /** 極高消耗，需要旗艦顯卡，包含 RT 功能 */
        ULTRA("旗艦", 0xFFFF4444);

        public final String label;
        public final int labelColor;

        PerformanceTier(String label, int labelColor) {
            this.label = label;
            this.labelColor = labelColor;
        }
    }

    // -------------------------------------------------------------------------
    // 欄位
    // -------------------------------------------------------------------------

    /** 預設唯一識別碼，例如 "blockreality:cinematic" */
    private final String id;

    /** 顯示名稱（繁體中文） */
    private final String displayName;

    /** 一行描述文字 */
    private final String description;

    /**
     * 縮圖材質路徑（Minecraft ResourceLocation 格式）。
     * 若為 null 則顯示色塊占位符。
     */
    private final String thumbnailPath;

    /** 效能評級 */
    private final PerformanceTier tier;

    /**
     * 節點埠覆蓋值表。
     * key = "nodeTypeId.portName"，例如 "bloom.threshold"
     * value = 覆蓋值（Float / Boolean / Integer / String）
     */
    private final Map<String, Object> portOverrides;

    /** 是否為使用者自建預設（允許刪除） */
    private final boolean userDefined;

    // -------------------------------------------------------------------------
    // 建構子（透過 Builder 使用）
    // -------------------------------------------------------------------------

    private StylePreset(Builder b) {
        this.id            = b.id;
        this.displayName   = b.displayName;
        this.description   = b.description;
        this.thumbnailPath = b.thumbnailPath;
        this.tier          = b.tier;
        this.portOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(b.portOverrides));
        this.userDefined   = b.userDefined;
    }

    // -------------------------------------------------------------------------
    // 公開存取
    // -------------------------------------------------------------------------

    public String getId()            { return id; }
    public String getDisplayName()   { return displayName; }
    public String getDescription()   { return description; }
    public String getThumbnailPath() { return thumbnailPath; }
    public PerformanceTier getTier() { return tier; }
    public Map<String, Object> getPortOverrides() { return portOverrides; }
    public boolean isUserDefined()   { return userDefined; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    public static final class Builder {
        private final String id;
        private final String displayName;
        private String description   = "";
        private String thumbnailPath = null;
        private PerformanceTier tier = PerformanceTier.BALANCED;
        private final Map<String, Object> portOverrides = new LinkedHashMap<>();
        private boolean userDefined = false;

        private Builder(String id, String displayName) {
            this.id          = id;
            this.displayName = displayName;
        }

        public Builder description(String desc)     { this.description   = desc;   return this; }
        public Builder thumbnail(String path)        { this.thumbnailPath = path;   return this; }
        public Builder tier(PerformanceTier t)       { this.tier          = t;      return this; }
        public Builder userDefined(boolean u)        { this.userDefined   = u;      return this; }

        /** 設定節點埠覆蓋：portKey 格式為 "nodeTypeId.portName" */
        public Builder override(String portKey, Object value) {
            portOverrides.put(portKey, value);
            return this;
        }

        // 快捷方法
        public Builder bloom(boolean on, float threshold, float intensity) {
            return override("bloom.enabled", on)
                  .override("bloom.threshold", threshold)
                  .override("bloom.intensity", intensity);
        }
        public Builder ssao(boolean on, float radius, int samples) {
            return override("ssao_gtao.enabled", on)
                  .override("ssao_gtao.radius", radius)
                  .override("ssao_gtao.samples", samples);
        }
        public Builder colorGrading(float saturation, float contrast, float shadows, float highlights) {
            return override("color_grading.saturation", saturation)
                  .override("color_grading.contrast", contrast)
                  .override("color_grading.shadows", shadows)
                  .override("color_grading.highlights", highlights);
        }
        public Builder tonemap(String mode) {
            return override("tonemap.mode", mode);
        }
        public Builder taa(boolean on, float blendFactor) {
            return override("taa.enabled", on)
                  .override("taa.blendFactor", blendFactor);
        }
        public Builder shadow(int resolution, float maxDist, boolean contact) {
            return override("shadow_config.resolution", resolution)
                  .override("shadow_config.maxDistance", maxDist)
                  .override("contact_shadow.enabled", contact);
        }
        public Builder dof(boolean on, float focalLength, float aperture) {
            return override("dof.enabled", on)
                  .override("dof.focalLength", focalLength)
                  .override("dof.aperture", aperture);
        }
        public Builder atmosphere(boolean clouds, boolean weather, boolean volumetric) {
            return override("cloud.enabled", clouds)
                  .override("atmosphere.enabled", weather)
                  .override("volumetric_light.enabled", volumetric);
        }
        public Builder filmGrain(boolean on, float strength) {
            return override("film_grain.enabled", on)
                  .override("film_grain.strength", strength);
        }
        public Builder vignette(boolean on, float radius, float softness) {
            return override("vignette.enabled", on)
                  .override("vignette.radius", radius)
                  .override("vignette.softness", softness);
        }
        public Builder outline(boolean on, float thickness, int color) {
            return override("outline.enabled", on)
                  .override("outline.thickness", thickness)
                  .override("outline.color", color);
        }
        public Builder pixelArt(boolean on, int pixelSize) {
            return override("pixel_art.enabled", on)
                  .override("pixel_art.pixelSize", pixelSize);
        }
        public Builder chromatic(boolean on, float strength) {
            return override("chromatic_aberration.enabled", on)
                  .override("chromatic_aberration.strength", strength);
        }
        public Builder crt(boolean on, float scanlineIntensity, float curvature) {
            return override("crt.enabled", on)
                  .override("crt.scanlineIntensity", scanlineIntensity)
                  .override("crt.curvature", curvature);
        }
        public Builder lut(boolean on, String lutPath, float intensity) {
            return override("color_grading_lut.enabled", on)
                  .override("color_grading_lut.lutPath", lutPath)
                  .override("color_grading_lut.intensity", intensity);
        }

        public StylePreset build() {
            return new StylePreset(this);
        }
    }
}
