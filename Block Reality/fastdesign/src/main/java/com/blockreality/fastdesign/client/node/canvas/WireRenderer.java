package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * 貝茲曲線連線渲染器 — 設計報告 §10.1, §12.1 N2-3
 *
 * 使用三次貝茲曲線連接端口，帶流動粒子動畫表示資料流向。
 */
public class WireRenderer {

    /** ★ review-fix ICReM-8: 增加線段數使曲線更平滑 */
    private static final int SEGMENTS = 48;
    private static final float WIRE_WIDTH = 2.0f;
    /** ★ review-fix ICReM-8: 增加流動粒子數量 + 速度微調 */
    private static final int PARTICLE_COUNT = 4;
    private static final float PARTICLE_SPEED = 0.4f;

    /**
     * 渲染已連接的 Wire。
     */
    public void renderWire(GuiGraphics gui, Wire wire, CanvasTransform transform, float partialTick) {
        float[] fromPos = NodeWidgetRenderer.getPortScreenPos(wire.from(), transform);
        float[] toPos = NodeWidgetRenderer.getPortScreenPos(wire.to(), transform);

        int color = wire.wireColor();
        float alpha = wire.isAutoConverted() ? 0.7f : 1.0f;

        // ★ review-fix ICReM-8: 陰影光暈（半透明暗色底線提高對比度）
        renderBezier(gui, fromPos[0], fromPos[1], toPos[0], toPos[1], 0x000000, alpha * 0.3f);
        renderBezier(gui, fromPos[0], fromPos[1], toPos[0], toPos[1], color, alpha);

        // 流動粒子（更柔和的脈衝樣式）
        renderFlowingPulse(gui, fromPos[0], fromPos[1], toPos[0], toPos[1], color, partialTick);

        // 自動轉換標記
        if (wire.isAutoConverted()) {
            String label = TypeChecker.conversionLabel(wire.fromType(), wire.toType());
            if (label != null) {
                float midX = (fromPos[0] + toPos[0]) / 2;
                float midY = (fromPos[1] + toPos[1]) / 2 - 8;
                gui.drawString(net.minecraft.client.Minecraft.getInstance().font,
                        label, (int) midX - 4, (int) midY, 0xFFFFFF00);
            }
        }
    }

    /**
     * 渲染拖曳中的臨時連線。
     */
    public void renderTempWire(GuiGraphics gui, float fromX, float fromY,
                                float toX, float toY, int color) {
        renderBezier(gui, fromX, fromY, toX, toY, color, 0.5f);
    }

    /**
     * 三次貝茲曲線（水平切線）。
     */
    private void renderBezier(GuiGraphics gui, float x1, float y1, float x2, float y2,
                               int color, float alpha) {
        // ★ ICReM-9: 控制點自適應 — 水平距離小時使用垂直偏移
        float hdx = Math.abs(x2 - x1);
        float vdy = Math.abs(y2 - y1);
        float tangentLen = Math.max(hdx * 0.5f, Math.min(vdy * 0.3f, 80.0f));
        tangentLen = Math.max(tangentLen, 30.0f); // 最小曲率
        float cx1 = x1 + tangentLen;
        float cy1 = y1;
        float cx2 = x2 - tangentLen;
        float cy2 = y2;

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        // 用連續線段逼近貝茲
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= SEGMENTS; i++) {
            float t = (float) i / SEGMENTS;
            float it = 1 - t;
            float px = it * it * it * x1 + 3 * it * it * t * cx1
                    + 3 * it * t * t * cx2 + t * t * t * x2;
            float py = it * it * it * y1 + 3 * it * it * t * cy1
                    + 3 * it * t * t * cy2 + t * t * t * y2;
            buf.vertex(px, py, 0).color(r, g, b, alpha).endVertex();
        }

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    /**
     * 沿貝茲曲線繪製柔和的脈衝光暈，取代原本生硬的小圓點
     */
    private void renderFlowingPulse(GuiGraphics gui,
                                     float x1, float y1, float x2, float y2,
                                     int color, float partialTick) {
        float hdx = Math.abs(x2 - x1);
        float vdy = Math.abs(y2 - y1);
        float tangentLen = Math.max(hdx * 0.5f, Math.min(vdy * 0.3f, 80.0f));
        tangentLen = Math.max(tangentLen, 30.0f);
        float cx1 = x1 + tangentLen, cy1 = y1;
        float cx2 = x2 - tangentLen, cy2 = y2;

        long time = System.currentTimeMillis();
        float baseT = (time * PARTICLE_SPEED / 1000.0f) % 1.0f;
        int segments = 48; // 使用和畫線一樣的精度

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float it = 1 - t;
            float px = it * it * it * x1 + 3 * it * it * t * cx1
                    + 3 * it * t * t * cx2 + t * t * t * x2;
            float py = it * it * it * y1 + 3 * it * it * t * cy1
                    + 3 * it * t * t * cy2 + t * t * t * y2;

            // 計算此點是否在脈衝範圍內
            float dist = Math.abs(t - baseT);
            if (dist > 0.5f) dist = 1.0f - dist; // 循環距離

            float pulseIntensity = Math.max(0, 1.0f - (dist * 5.0f)); // 脈衝長度
            float alpha = pulseIntensity * 0.8f;

            if (alpha > 0) {
                // 加亮效果
                buf.vertex(px, py, 0).color(Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.5f), alpha).endVertex();
            } else {
                buf.vertex(px, py, 0).color(0f, 0f, 0f, 0f).endVertex();
            }
        }

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    private static int brighten(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
