package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.pipeline.RenderPassContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block Reality 特效渲染器 — 管理所有視覺特效子系統。
 *
 * 子系統：
 *   1. SelectionBoxRenderer — 增強選框（發光脈衝、排除標記）
 *   2. PlacementFXRenderer — 方塊放置粒子特效
 *   3. StructuralFXRenderer — 結構應力閃爍、崩塌碎片
 *   4. UIOverlayRenderer — HUD 覆蓋層資訊
 *
 * 分離為「半透明幾何」和「覆蓋層」兩個渲染時機：
 *   - renderTranslucentGeometry(): 在 GBuffer translucent pass 中呼叫
 *   - renderOverlays(): 在 AFTER_LEVEL overlay pass 中呼叫
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
@OnlyIn(Dist.CLIENT)
public final class BREffectRenderer {
    private BREffectRenderer() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Effect");

    private static SelectionBoxRenderer selectionRenderer;
    private static PlacementFXRenderer placementRenderer;
    private static StructuralFXRenderer structuralRenderer;
    private static UIOverlayRenderer uiRenderer;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        selectionRenderer  = new SelectionBoxRenderer();
        placementRenderer  = new PlacementFXRenderer();
        structuralRenderer = new StructuralFXRenderer();
        uiRenderer         = new UIOverlayRenderer();

        initialized = true;
        LOG.info("特效渲染器初始化完成 — 4 子系統");
    }

    public static void cleanup() {
        if (selectionRenderer != null)  selectionRenderer.cleanup();
        if (placementRenderer != null)  placementRenderer.cleanup();
        if (structuralRenderer != null) structuralRenderer.cleanup();
        if (uiRenderer != null)         uiRenderer.cleanup();
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  渲染入口
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染半透明幾何特效 — 在 GBUFFER_TRANSLUCENT pass 中呼叫。
     * 包含：選框半透明面、幽靈方塊、排除標記方塊。
     */
    public static void renderTranslucentGeometry(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (!initialized) return;
        selectionRenderer.renderTranslucent(projMatrix, viewMatrix);
    }

    /** RenderPassContext 版本（BRRenderPipeline 入口） */
    public static void renderTranslucentGeometry(RenderPassContext ctx) {
        renderTranslucentGeometry(ctx.getProjectionMatrix(), ctx.getViewMatrix());
    }

    /**
     * 渲染覆蓋層 — 在 OVERLAY pass 中呼叫。
     * 包含：選框線框、放置粒子、應力效果、HUD 資訊。
     */
    public static void renderOverlays(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (!initialized) return;
        selectionRenderer.renderWireframe(projMatrix, viewMatrix);
        placementRenderer.render(projMatrix, viewMatrix);
        structuralRenderer.render(projMatrix, viewMatrix);
        uiRenderer.render(projMatrix, viewMatrix);
    }

    /** RenderPassContext 版本（BRRenderPipeline 入口） */
    public static void renderOverlays(RenderPassContext ctx) {
        renderOverlays(ctx.getProjectionMatrix(), ctx.getViewMatrix());
    }

    // ─── 子系統 Accessor ────────────────────────────────

    public static SelectionBoxRenderer getSelectionRenderer() { return selectionRenderer; }
    public static PlacementFXRenderer getPlacementRenderer() { return placementRenderer; }
    public static StructuralFXRenderer getStructuralRenderer() { return structuralRenderer; }
    public static UIOverlayRenderer getUIRenderer() { return uiRenderer; }
}

