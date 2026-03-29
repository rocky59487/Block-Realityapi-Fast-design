package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.BRNode;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌預覽渲染器 — 設計報告 §12.1 N2-7
 *
 * 在節點內部渲染 64×64 的預覽縮圖：
 *   - AO map（灰階遮蔽圖）
 *   - 材料雷達圖（6 軸）
 *   - 3D 等角投影形狀
 *   - 收斂曲線（iteration vs residual）
 *   - 養護進度曲線
 *   - 方塊 3D 旋轉預覽
 *
 * 使用離屏 FBO 或 GuiGraphics 直接繪製。
 */
public class InlinePreviewRenderer {

    private static final int PREVIEW_SIZE = 64;
    private static final int PREVIEW_BG = 0xFF0A0A14;
    private static final int PREVIEW_BORDER = 0xFF3A3A5A;

    /**
     * 渲染預覽框背景。
     */
    public void renderPreviewFrame(GuiGraphics gui, int x, int y, int size) {
        gui.fill(x, y, x + size, y + size, PREVIEW_BG);
        gui.fill(x, y, x + size, y + 1, PREVIEW_BORDER);
        gui.fill(x, y + size - 1, x + size, y + size, PREVIEW_BORDER);
        gui.fill(x, y, x + 1, y + size, PREVIEW_BORDER);
        gui.fill(x + size - 1, y, x + size, y + size, PREVIEW_BORDER);
    }

    /**
     * 渲染雷達圖（6 軸：comp/tens/shear/density/E/v）。
     */
    public void renderRadarChart(GuiGraphics gui, int cx, int cy, int radius,
                                  float[] values, int color) {
        if (values == null || values.length < 6) return;

        int axes = 6;
        float angleStep = (float) (2 * Math.PI / axes);

        // 軸線
        for (int i = 0; i < axes; i++) {
            float angle = angleStep * i - (float) Math.PI / 2;
            int ex = cx + (int) (Math.cos(angle) * radius);
            int ey = cy + (int) (Math.sin(angle) * radius);
            drawLine(gui, cx, cy, ex, ey, 0xFF333355);
        }

        // 數值多邊形
        int prevX = 0, prevY = 0;
        int firstX = 0, firstY = 0;
        for (int i = 0; i <= axes; i++) {
            int idx = i % axes;
            float angle = angleStep * idx - (float) Math.PI / 2;
            float r = Math.max(0, Math.min(1, values[idx])) * radius;
            int px = cx + (int) (Math.cos(angle) * r);
            int py = cy + (int) (Math.sin(angle) * r);

            if (i == 0) {
                firstX = px;
                firstY = py;
            } else {
                drawLine(gui, prevX, prevY, px, py, color);
            }
            prevX = px;
            prevY = py;

            // 頂點
            gui.fill(px - 1, py - 1, px + 2, py + 2, color);
        }
    }

    /**
     * 渲染曲線圖（如收斂曲線、養護曲線）。
     */
    public void renderCurve(GuiGraphics gui, int x, int y, int w, int h,
                             float[] data, int color) {
        if (data == null || data.length < 2) return;

        renderPreviewFrame(gui, x, y, Math.min(w, h));

        float maxVal = Float.MIN_VALUE;
        for (float v : data) maxVal = Math.max(maxVal, Math.abs(v));
        if (maxVal == 0) maxVal = 1;

        int prevPx = 0, prevPy = 0;
        for (int i = 0; i < data.length; i++) {
            int px = x + (int) ((float) i / (data.length - 1) * (w - 4)) + 2;
            int py = y + h - 2 - (int) (Math.abs(data[i]) / maxVal * (h - 4));

            if (i > 0) {
                drawLine(gui, prevPx, prevPy, px, py, color);
            }
            prevPx = px;
            prevPy = py;
        }
    }

    /**
     * 渲染色階條（如應力熱力圖色條）。
     */
    public void renderColorBar(GuiGraphics gui, int x, int y, int w, int h,
                                int colorLow, int colorHigh) {
        for (int i = 0; i < w; i++) {
            float t = (float) i / w;
            int r = (int) lerp(((colorLow >> 16) & 0xFF), ((colorHigh >> 16) & 0xFF), t);
            int g = (int) lerp(((colorLow >> 8) & 0xFF), ((colorHigh >> 8) & 0xFF), t);
            int b = (int) lerp((colorLow & 0xFF), (colorHigh & 0xFF), t);
            gui.fill(x + i, y, x + i + 1, y + h, 0xFF000000 | (r << 16) | (g << 8) | b);
        }
    }

    // ─── 工具 ───

    private void drawLine(GuiGraphics gui, int x1, int y1, int x2, int y2, int color) {
        // 簡化的 Bresenham
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int steps = 0;
        int maxSteps = dx + dy + 1;
        while (steps++ < maxSteps) {
            gui.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
