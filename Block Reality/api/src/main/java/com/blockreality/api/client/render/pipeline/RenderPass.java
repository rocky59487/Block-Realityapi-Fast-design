package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 渲染 Pass 枚舉 — 仿 Iris/ShadersMod 渲染管線架構。
 *
 * 固化管線 pass 順序：
 *   SHADOW → GBUFFER_TERRAIN → GBUFFER_ENTITIES → GBUFFER_BLOCK_ENTITIES
 *   → GBUFFER_TRANSLUCENT → DEFERRED_LIGHTING → COMPOSITE_SSAO
 *   → COMPOSITE_BLOOM → COMPOSITE_TONEMAP → FINAL → OVERLAY_UI → OVERLAY_EFFECT
 *
 * 每個 pass 對應一組固定的 FBO 綁定和 shader program。
 *
 * @deprecated Since 2.0, replaced by Vulkan RT + Voxy LOD pipeline in com.blockreality.api.client.rendering
 */
@Deprecated(since = "2.0", forRemoval = true)
@OnlyIn(Dist.CLIENT)
public enum RenderPass {

    // ── Phase 1: Shadow ──────────────────────────────────
    /** 從光源視角渲染深度圖（Iris shadow pass） */
    SHADOW("shadow", true, false),

    // ── Phase 2: GBuffer 幾何填充（Iris gbuffers_*） ─────
    /** 地形幾何 — position/normal/albedo/material 寫入 GBuffer */
    GBUFFER_TERRAIN("gbuffer_terrain", false, true),

    /** 實體幾何（未來人物動畫用） */
    GBUFFER_ENTITIES("gbuffer_entities", false, true),

    /** 方塊實體（RBlockEntity 自訂渲染） */
    GBUFFER_BLOCK_ENTITIES("gbuffer_block_entities", false, true),

    /** 半透明幾何（幽靈方塊、選框半透明面） */
    GBUFFER_TRANSLUCENT("gbuffer_translucent", false, true),

    // ── Phase 3: Deferred Lighting（Iris deferred pass） ──
    /** 延遲光照 — 讀取 GBuffer + ShadowMap → 計算最終光照 */
    DEFERRED_LIGHTING("deferred_lighting", false, false),

    // ── Phase 4: Composite（Iris composite pass） ────────
    /** SSAO 環境光遮蔽 */
    COMPOSITE_SSAO("composite_ssao", false, false),

    /** Bloom 泛光提取 + 模糊 */
    COMPOSITE_BLOOM("composite_bloom", false, false),

    /** Tone Mapping + Gamma 校正 */
    COMPOSITE_TONEMAP("composite_tonemap", false, false),

    // ── Phase 5: Final ──────────────────────────────────
    /** 最終合成 — 輸出到螢幕 back buffer */
    FINAL("final", false, false),

    // ── Phase 6: Overlay（BR 專屬 — 不參與延遲管線） ─────
    /** UI 覆蓋層 — HUD、工具提示、選框資訊 */
    OVERLAY_UI("overlay_ui", false, false),

    /** 特效覆蓋層 — 放置動畫、崩塌碎片、應力閃爍 */
    OVERLAY_EFFECT("overlay_effect", false, false);

    private final String id;
    private final boolean shadowCaster;
    private final boolean writesGBuffer;

    RenderPass(String id, boolean shadowCaster, boolean writesGBuffer) {
        this.id = id;
        this.shadowCaster = shadowCaster;
        this.writesGBuffer = writesGBuffer;
    }

    public String getId() { return id; }

    /** 此 pass 是否產生 shadow map */
    public boolean isShadowCaster() { return shadowCaster; }

    /** 此 pass 是否寫入 GBuffer 附件 */
    public boolean writesGBuffer() { return writesGBuffer; }

    /** 是否為 composite/deferred 後處理 pass */
    public boolean isPostProcess() {
        return this == DEFERRED_LIGHTING || this == COMPOSITE_SSAO
            || this == COMPOSITE_BLOOM || this == COMPOSITE_TONEMAP || this == FINAL;
    }

    /** 是否為 overlay（不受延遲管線影響） */
    public boolean isOverlay() {
        return this == OVERLAY_UI || this == OVERLAY_EFFECT;
    }
}
