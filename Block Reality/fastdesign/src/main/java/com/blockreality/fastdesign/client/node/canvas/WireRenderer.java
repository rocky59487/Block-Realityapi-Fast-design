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

    private static final int SEGMENTS = 32;
    private static final float WIRE_WIDTH = 2.0f;
    private static final int PARTICLE_COUNT = 3;
    private static final float PARTICLE_SPEED = 0.5f;

    /**
     * 渲染已連接的 Wire。
     */
    public void renderWire(GuiGraphics gui, Wire wire, CanvasTransform transform, float partialTick) {
        float[] fromPos = NodeWidgetRenderer.getPortScreenPos(wire.from(), transform);
        float[] toPos = NodeWidgetRenderer.getPortScreenPos(wire.to(), transform);

        int color = wire.wireColor();
        float alpha = wire.isAutoConverted() ? 0.7f : 1.0f;

        renderBezier(gui, fromPos[0], fromPos[1], toPos[0], toPos[1], color, alpha);

        // 流動粒子
        renderFlowingParticles(gui, fromPos[0], fromPos[1], toPos[0], toPos[1], color, partialTick);

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
        // 控制點：水平方向延伸
        float dx = Math.abs(x2 - x1) * 0.5f;
        float cx1 = x1 + dx;
        float cy1 = y1;
        float cx2 = x2 - dx;
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
     * 流動粒子（沿貝茲曲線的小圓點）。
     */
    private void renderFlowingParticles(GuiGraphics gui,
                                         float x1, float y1, float x2, float y2,
                                         int color, float partialTick) {
        float dx = Math.abs(x2 - x1) * 0.5f;
        float cx1 = x1 + dx, cy1 = y1;
        float cx2 = x2 - dx, cy2 = y2;

        long time = System.currentTimeMillis();
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float t = ((time * PARTICLE_SPEED / 1000.0f + (float) i / PARTICLE_COUNT) % 1.0f);
            float it = 1 - t;
            float px = it * it * it * x1 + 3 * it * it * t * cx1
                    + 3 * it * t * t * cx2 + t * t * t * x2;
            float py = it * it * it * y1 + 3 * it * it * t * cy1
                    + 3 * it * t * t * cy2 + t * t * t * y2;

            int brightColor = brighten(color, 1.5f);
            gui.fill((int) px - 2, (int) py - 2, (int) px + 2, (int) py + 2, brightColor);
        }
    }

    private static int brighten(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
