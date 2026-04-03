package com.blockreality.fastdesign.client.node.panel;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.*;

/**
 * 集中管理所有內建與使用者自定義的 {@link StylePreset}。
 *
 * <p>內建預設共 8 種風格，涵蓋從最輕量到旗艦光追的全光譜，
 * 每種風格都透過 {@link StylePreset.Builder} 宣告完整的節點埠覆蓋值，
 * 確保套用後的結果完全由節點圖驅動，不繞過渲染管線。
 *
 * <p>使用者可透過「從目前設定儲存」功能新增自定義預設，
 * 自定義預設標記 {@code userDefined = true}，允許從 UI 刪除。
 */
@OnlyIn(Dist.CLIENT)
public final class StylePresetRegistry {

    // -------------------------------------------------------------------------
    // 單例
    // -------------------------------------------------------------------------

    private static final StylePresetRegistry INSTANCE = new StylePresetRegistry();
    public static StylePresetRegistry getInstance() { return INSTANCE; }

    // -------------------------------------------------------------------------
    // 狀態
    // -------------------------------------------------------------------------

    /** 有序預設列表（插入順序 = 顯示順序） */
    private final LinkedHashMap<String, StylePreset> presets = new LinkedHashMap<>();

    /** 目前啟用的預設 ID，"custom" 代表使用者在節點圖中手動調整後的狀態 */
    private String activePresetId = "blockreality:balanced";

    // -------------------------------------------------------------------------
    // 建構子（私有，單例）
    // -------------------------------------------------------------------------

    private StylePresetRegistry() {
        registerBuiltins();
    }

    // -------------------------------------------------------------------------
    // 內建預設宣告
    // -------------------------------------------------------------------------

    private void registerBuiltins() {

        // 1. 寫實電影 — 旗艦消耗，全特效開啟，ACES 色調映射
        register(StylePreset.builder("blockreality:cinematic", "寫實電影")
            .description("如電影場景般的光影效果，開啟全部後製特效與光線追蹤")
            .thumbnail("blockreality:textures/ui/preset/cinematic.png")
            .tier(StylePreset.PerformanceTier.ULTRA)
            .shadow(4096, 128f, true)
            .bloom(true, 0.85f, 1.2f)
            .ssao(true, 2.0f, 64)
            .taa(true, 0.92f)
            .colorGrading(1.1f, 1.05f, -0.05f, 0.08f)
            .tonemap("ACES")
            .dof(true, 55f, 1.4f)
            .atmosphere(true, true, true)
            .filmGrain(true, 0.04f)
            .vignette(true, 0.75f, 0.45f)
            .chromatic(true, 0.003f)
            .lut(true, "blockreality:luts/cinematic.png", 0.85f)
            .override("ssr.enabled", true)
            .override("ssgi.enabled", true)
            .override("sss.enabled", true)
            .override("motion_blur.enabled", true)
            .build());

        // 2. 動漫卡通 — 中等消耗，描邊 + 簡化色調 + 低飽和度 AO
        register(StylePreset.builder("blockreality:anime", "動漫卡通")
            .description("乾淨的賽璐珞描邊風格，色彩鮮明、陰影簡潔，輕量運行")
            .thumbnail("blockreality:textures/ui/preset/anime.png")
            .tier(StylePreset.PerformanceTier.BALANCED)
            .shadow(2048, 64f, false)
            .bloom(true, 0.6f, 0.8f)
            .ssao(false, 1.0f, 16)
            .taa(true, 0.88f)
            .colorGrading(1.4f, 0.9f, 0.05f, 0.12f)
            .tonemap("REINHARD")
            .dof(false, 50f, 2.8f)
            .atmosphere(true, false, false)
            .filmGrain(false, 0f)
            .vignette(false, 0.8f, 0.5f)
            .outline(true, 1.5f, 0xFF1A1A2E)
            .lut(true, "blockreality:luts/anime.png", 0.90f)
            .override("ssr.enabled", false)
            .build());

        // 3. 霓虹賽博龐克 — 高消耗，強 Bloom、發光描邊、藍紫色調
        register(StylePreset.builder("blockreality:cyberpunk", "霓虹賽博龐克")
            .description("賽博都市夜景，強烈霓虹 Bloom 與色差效果，充滿未來感")
            .thumbnail("blockreality:textures/ui/preset/cyberpunk.png")
            .tier(StylePreset.PerformanceTier.DEMANDING)
            .shadow(2048, 96f, true)
            .bloom(true, 0.4f, 2.8f)
            .ssao(true, 1.2f, 32)
            .taa(true, 0.90f)
            .colorGrading(0.9f, 1.2f, -0.15f, 0.25f)
            .tonemap("UNCHARTED2")
            .dof(false, 50f, 1.8f)
            .atmosphere(false, false, true)
            .filmGrain(true, 0.06f)
            .vignette(true, 0.65f, 0.6f)
            .chromatic(true, 0.008f)
            .lut(true, "blockreality:luts/cyberpunk.png", 1.0f)
            .override("ssr.enabled", true)
            .override("wet_pbr.enabled", true)
            .build());

        // 4. 夢幻粉彩 — 輕量，低對比、高亮度、柔和 Bloom
        register(StylePreset.builder("blockreality:dreamy", "夢幻粉彩")
            .description("柔和粉色系，低對比度高亮度，如夢似幻的空靈感")
            .thumbnail("blockreality:textures/ui/preset/dreamy.png")
            .tier(StylePreset.PerformanceTier.LITE)
            .shadow(1024, 48f, false)
            .bloom(true, 0.3f, 1.6f)
            .ssao(false, 1.0f, 16)
            .taa(true, 0.85f)
            .colorGrading(0.8f, 0.75f, 0.10f, 0.20f)
            .tonemap("REINHARD")
            .dof(true, 85f, 5.6f)
            .atmosphere(true, false, false)
            .filmGrain(false, 0f)
            .vignette(true, 0.9f, 0.7f)
            .lut(true, "blockreality:luts/dreamy.png", 0.75f)
            .build());

        // 5. 復古像素 — 極輕量，像素化 + CRT 掃描線，關閉所有平滑處理
        register(StylePreset.builder("blockreality:retro_pixel", "復古像素")
            .description("8-bit 懷舊像素風，開啟 CRT 掃描線，關閉抗鋸齒與平滑效果")
            .thumbnail("blockreality:textures/ui/preset/retro_pixel.png")
            .tier(StylePreset.PerformanceTier.LITE)
            .shadow(512, 32f, false)
            .bloom(false, 1.0f, 0f)
            .ssao(false, 1.0f, 8)
            .taa(false, 0.5f)
            .colorGrading(1.0f, 1.1f, 0f, 0f)
            .tonemap("LINEAR")
            .dof(false, 50f, 16f)
            .atmosphere(false, false, false)
            .filmGrain(false, 0f)
            .vignette(false, 0.8f, 0.5f)
            .pixelArt(true, 4)
            .crt(true, 0.5f, 0.15f)
            .build());

        // 6. Lo-Fi 懷舊 — 輕量，膠片顆粒感 + 褪色 LUT + 暈影
        register(StylePreset.builder("blockreality:lofi", "Lo-Fi 懷舊")
            .description("如老相片的溫暖色調，膠片顆粒與輕微暈影，低消耗高質感")
            .thumbnail("blockreality:textures/ui/preset/lofi.png")
            .tier(StylePreset.PerformanceTier.LITE)
            .shadow(1024, 48f, false)
            .bloom(false, 1.0f, 0f)
            .ssao(true, 0.8f, 16)
            .taa(true, 0.85f)
            .colorGrading(0.85f, 0.88f, 0.08f, -0.05f)
            .tonemap("REINHARD")
            .dof(false, 50f, 4.0f)
            .atmosphere(false, false, false)
            .filmGrain(true, 0.12f)
            .vignette(true, 0.7f, 0.55f)
            .chromatic(true, 0.002f)
            .lut(true, "blockreality:luts/lofi.png", 0.80f)
            .build());

        // 7. 均衡（預設） — 中等消耗，各功能適度開啟
        register(StylePreset.builder("blockreality:balanced", "均衡")
            .description("效能與畫質的最佳平衡點，適合大多數中階顯卡")
            .thumbnail("blockreality:textures/ui/preset/balanced.png")
            .tier(StylePreset.PerformanceTier.BALANCED)
            .shadow(2048, 96f, false)
            .bloom(true, 0.9f, 0.9f)
            .ssao(true, 1.5f, 32)
            .taa(true, 0.90f)
            .colorGrading(1.0f, 1.0f, 0f, 0f)
            .tonemap("ACES")
            .dof(false, 50f, 2.8f)
            .atmosphere(true, true, false)
            .filmGrain(false, 0f)
            .vignette(false, 0.8f, 0.5f)
            .build());

        // 8. 效能優先 — 極輕量，關閉所有後製，最大 FPS
        register(StylePreset.builder("blockreality:performance", "效能優先")
            .description("關閉所有後製特效，最大化幀率，適合低階硬體或測試環境")
            .thumbnail("blockreality:textures/ui/preset/performance.png")
            .tier(StylePreset.PerformanceTier.LITE)
            .shadow(1024, 48f, false)
            .bloom(false, 1.0f, 0f)
            .ssao(false, 1.0f, 8)
            .taa(false, 0.5f)
            .colorGrading(1.0f, 1.0f, 0f, 0f)
            .tonemap("LINEAR")
            .dof(false, 50f, 16f)
            .atmosphere(false, false, false)
            .filmGrain(false, 0f)
            .vignette(false, 0.8f, 0.5f)
            .build());
    }

    // -------------------------------------------------------------------------
    // 公開 API
    // -------------------------------------------------------------------------

    /** 取得所有已注冊的預設（有序）。 */
    public List<StylePreset> getAllPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets.values()));
    }

    /** 依 ID 取得預設，若不存在回傳 null。 */
    public StylePreset getById(String id) {
        return presets.get(id);
    }

    /** 取得目前啟用的預設，若為自定義狀態則回傳 null。 */
    public StylePreset getActivePreset() {
        return presets.get(activePresetId);
    }

    /** 取得目前啟用的預設 ID（"custom" 代表節點圖手動狀態）。 */
    public String getActivePresetId() {
        return activePresetId;
    }

    /** 設定啟用的預設 ID（不套用，僅記錄狀態）。 */
    public void setActivePresetId(String id) {
        this.activePresetId = id;
    }

    /**
     * 新增或覆蓋一個預設（用於使用者自定義預設）。
     * 使用者預設必須 {@code isUserDefined() == true}，
     * 否則拒絕覆蓋內建預設。
     */
    public boolean register(StylePreset preset) {
        StylePreset existing = presets.get(preset.getId());
        if (existing != null && !existing.isUserDefined()) {
            // 不允許覆蓋內建預設
            return false;
        }
        presets.put(preset.getId(), preset);
        return true;
    }

    /**
     * 刪除一個使用者自定義預設。內建預設無法刪除。
     */
    public boolean remove(String id) {
        StylePreset p = presets.get(id);
        if (p == null || !p.isUserDefined()) return false;
        presets.remove(id);
        if (id.equals(activePresetId)) activePresetId = "blockreality:balanced";
        return true;
    }

    /**
     * 標記為「自定義」狀態（使用者在節點圖中手動改變了任何埠值）。
     */
    public void markCustom() {
        this.activePresetId = "custom";
    }

    /** 是否處於自定義（節點手動）狀態。 */
    public boolean isCustom() {
        return "custom".equals(activePresetId);
    }
}
