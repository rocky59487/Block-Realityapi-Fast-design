package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.pipeline.RenderPassContext;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.Set;

/**
 * 增強選框渲染器 — 取代原有的簡單線框。
 *
 * 特效：
 *   1. 脈衝發光邊框（sin 波週期性 alpha 變化）
 *   2. 半透明填充面（選取區域視覺化）
 *   3. 排除方塊標記（紅色 X 十字覆蓋）
 *   4. 動畫過渡（選框大小改變時平滑插值）
 *
 * Shader 使用：
 *   - 線框部分使用 selection_glow shader（帶脈衝 uniform）
 *   - 半透明部分使用 translucent shader
 *   - 排除標記使用 overlay shader
 */
@OnlyIn(Dist.CLIENT)
public final class SelectionBoxRenderer {

    // ─── 選框狀態 ───────────────────────────────────────
    private BlockPos min = null;
    private BlockPos max = null;
    private Set<BlockPos> excludedPositions = Collections.emptySet();

    // 動畫插值用
    private float animMinX, animMinY, animMinZ;
    private float animMaxX, animMaxY, animMaxZ;
    private boolean hasTarget = false;

    // 選框顏色（FastDesign 藍色系）
    private static final float SEL_R = 0.3f, SEL_G = 0.6f, SEL_B = 1.0f;
    private static final float EXCLUDE_R = 1.0f, EXCLUDE_G = 0.2f, EXCLUDE_B = 0.2f;

    private static final float INSET = 0.002f;

    SelectionBoxRenderer() {}

    // ═══════════════════════════════════════════════════════
    //  狀態更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新選框範圍。
     */
    public void setSelection(BlockPos min, BlockPos max) {
        if (min == null || max == null) {
            this.min = null;
            this.max = null;
            hasTarget = false;
            return;
        }
        this.min = new BlockPos(
            Math.min(min.getX(), max.getX()),
            Math.min(min.getY(), max.getY()),
            Math.min(min.getZ(), max.getZ())
        );
        this.max = new BlockPos(
            Math.max(min.getX(), max.getX()),
            Math.max(min.getY(), max.getY()),
            Math.max(min.getZ(), max.getZ())
        );
        hasTarget = true;
    }

    /**
     * 更新排除方塊集合。
     */
    public void setExcluded(Set<BlockPos> excluded) {
        this.excludedPositions = excluded != null ? excluded : Collections.emptySet();
    }

    public void clear() {
        min = null;
        max = null;
        excludedPositions = Collections.emptySet();
        hasTarget = false;
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染半透明填充面（在 GBuffer translucent pass）。
     */
    public void renderTranslucent(RenderPassContext ctx) {
        if (min == null || max == null) return;

        smoothInterpolate(ctx.getPartialTick());

        // 使用 translucent shader（已由管線綁定）
        Tesselator tes = Tesselator.getInstance();
        BufferBuilder buf = tes.getBuilder();
        Matrix4f mat = ctx.getPoseStack().last().pose();

        // 取得 pose matrix（相對攝影機偏移已在 shader 處理）
        // 此處直接使用世界座標（shader 會減去 cameraPos）
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float alpha = 0.15f; // 很淡的填充
        addBox(buf, mat, animMinX, animMinY, animMinZ,
            animMaxX + 1, animMaxY + 1, animMaxZ + 1,
            SEL_R, SEL_G, SEL_B, alpha);

        tes.end();
    }

    /**
     * 渲染線框 + 發光效果 + 排除標記（在 Overlay pass）。
     */
    public void renderWireframe(RenderPassContext ctx) {
        if (min == null || max == null) return;

        smoothInterpolate(ctx.getPartialTick());

        // 計算脈衝相位
        int period = BRRenderConfig.SELECTION_GLOW_PERIOD;
        float phase = (ctx.getWorldTick() % period) / (float) period * (float)(Math.PI * 2);

        // 脈衝 alpha
        float glowAlpha = BRRenderConfig.SELECTION_GLOW_MIN_ALPHA +
            (BRRenderConfig.SELECTION_GLOW_MAX_ALPHA - BRRenderConfig.SELECTION_GLOW_MIN_ALPHA) *
            (0.5f + 0.5f * (float) Math.sin(phase));

        // 嘗試使用自訂 shader
        BRShaderProgram glowShader = BRShaderEngine.getSelectionGlowShader();
        if (glowShader != null && glowShader.isBound()) {
            glowShader.setUniformFloat("u_glowPhase", phase);
        }

        // Fallback: 使用原版 renderer（前向渲染模式）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.5f);

        Tesselator tes = Tesselator.getInstance();
        BufferBuilder buf = tes.getBuilder();

        // ── 選框線框 ──
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float x0 = animMinX - INSET;
        float y0 = animMinY - INSET;
        float z0 = animMinZ - INSET;
        float x1 = animMaxX + 1 + INSET;
        float y1 = animMaxY + 1 + INSET;
        float z1 = animMaxZ + 1 + INSET;

        int r = (int)(SEL_R * 255), g = (int)(SEL_G * 255), b = (int)(SEL_B * 255);
        int a = (int)(glowAlpha * 255);

        // 繪製 12 條邊（加上攝影機偏移——此處直接使用 world pos，
        // 因為 overlay pass 的 poseStack 已經處理了偏移）
        Matrix4f mat = ctx.getPoseStack().last().pose();

        // Bottom
        line(buf, mat, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, mat, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top
        line(buf, mat, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, mat, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, mat, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Vertical
        line(buf, mat, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y1, z1, r, g, b, a);

        tes.end();

        // ── 排除方塊標記（紅色 X）──
        if (!excludedPositions.isEmpty()) {
            int er = (int)(EXCLUDE_R * 255), eg = (int)(EXCLUDE_G * 255), eb = (int)(EXCLUDE_B * 255);
            int ea = 200;

            buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

            for (BlockPos pos : excludedPositions) {
                float px = pos.getX(), py = pos.getY(), pz = pos.getZ();
                // X 型十字（面對角線）
                // Top face X
                line(buf, mat, px, py + 1.01f, pz, px + 1, py + 1.01f, pz + 1, er, eg, eb, ea);
                line(buf, mat, px + 1, py + 1.01f, pz, px, py + 1.01f, pz + 1, er, eg, eb, ea);
                // South face X
                line(buf, mat, px, py, pz + 1.01f, px + 1, py + 1, pz + 1.01f, er, eg, eb, ea);
                line(buf, mat, px + 1, py, pz + 1.01f, px, py + 1, pz + 1.01f, er, eg, eb, ea);
            }

            tes.end();
        }

        // Restore
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
    }

    // ─── 平滑插值 ──────────────────────────────────────

    private void smoothInterpolate(float partialTick) {
        if (min == null || max == null) return;

        float targetMinX = min.getX(), targetMinY = min.getY(), targetMinZ = min.getZ();
        float targetMaxX = max.getX(), targetMaxY = max.getY(), targetMaxZ = max.getZ();

        if (!hasTarget) {
            animMinX = targetMinX; animMinY = targetMinY; animMinZ = targetMinZ;
            animMaxX = targetMaxX; animMaxY = targetMaxY; animMaxZ = targetMaxZ;
            return;
        }

        float lerp = 0.3f; // 平滑因子
        animMinX += (targetMinX - animMinX) * lerp;
        animMinY += (targetMinY - animMinY) * lerp;
        animMinZ += (targetMinZ - animMinZ) * lerp;
        animMaxX += (targetMaxX - animMaxX) * lerp;
        animMaxY += (targetMaxY - animMaxY) * lerp;
        animMaxZ += (targetMaxZ - animMaxZ) * lerp;
    }

    // ─── 工具 ───────────────────────────────────────────

    private static void addBox(BufferBuilder buf, Matrix4f mat,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float r, float g, float b, float a) {
        int ri = (int)(r * 255), gi = (int)(g * 255), bi = (int)(b * 255), ai = (int)(a * 255);
        // 6 faces × 4 vertices
        // Bottom
        buf.vertex(mat, x0, y0, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y0, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y0, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y0, z1).color(ri, gi, bi, ai).endVertex();
        // Top
        buf.vertex(mat, x0, y1, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y1, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z0).color(ri, gi, bi, ai).endVertex();
        // North
        buf.vertex(mat, x0, y0, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y1, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y0, z0).color(ri, gi, bi, ai).endVertex();
        // South
        buf.vertex(mat, x0, y0, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y0, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y1, z1).color(ri, gi, bi, ai).endVertex();
        // West
        buf.vertex(mat, x0, y0, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y0, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y1, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x0, y1, z0).color(ri, gi, bi, ai).endVertex();
        // East
        buf.vertex(mat, x1, y0, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z0).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y1, z1).color(ri, gi, bi, ai).endVertex();
        buf.vertex(mat, x1, y0, z1).color(ri, gi, bi, ai).endVertex();
    }

    private static void line(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }

    void cleanup() {
        clear();
    }
}
