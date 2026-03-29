package com.blockreality.fastdesign.client.node.canvas.control;

import com.blockreality.fastdesign.client.node.InputPort;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌曲線編輯器 — 設計報告 §12.1 N2-8
 *
 * 用於 CURVE 型別的輸入端口。
 * 顯示可拖曳控制點的 Bezier 曲線 / LUT 編輯器。
 */
public class InlineCurveEditor {

    private static final int EDITOR_W = 120;
    private static final int EDITOR_H = 60;
    private static final int BG_COLOR = 0xFF0A0A14;
    private static final int GRID_COLOR = 0xFF1A1A2A;
    private static final int CURVE_COLOR = 0xFF44AAFF;
    private static final int POINT_COLOR = 0xFFFFCC00;
    private static final int BORDER_COLOR = 0xFF3A3A5A;

    private int dragPointIndex = -1;

    /**
     * 渲染曲線編輯器。
     */
    public int render(GuiGraphics gui, InputPort port, int x, int y) {
        // 背景
        gui.fill(x, y, x + EDITOR_W, y + EDITOR_H, BG_COLOR);
        gui.fill(x, y, x + EDITOR_W, y + 1, BORDER_COLOR);
        gui.fill(x, y + EDITOR_H - 1, x + EDITOR_W, y + EDITOR_H, BORDER_COLOR);
        gui.fill(x, y, x + 1, y + EDITOR_H, BORDER_COLOR);
        gui.fill(x + EDITOR_W - 1, y, x + EDITOR_W, y + EDITOR_H, BORDER_COLOR);

        // 網格線
        for (int gx = 0; gx < EDITOR_W; gx += EDITOR_W / 4) {
            gui.fill(x + gx, y, x + gx + 1, y + EDITOR_H, GRID_COLOR);
        }
        for (int gy = 0; gy < EDITOR_H; gy += EDITOR_H / 4) {
            gui.fill(x, y + gy, x + EDITOR_W, y + gy + 1, GRID_COLOR);
        }

        // 曲線（從 port value 取得控制點）
        float[] data = getCurveData(port);
        if (data != null && data.length >= 2) {
            int prevPx = 0, prevPy = 0;
            for (int i = 0; i < data.length; i++) {
                int px = x + (int) ((float) i / (data.length - 1) * (EDITOR_W - 4)) + 2;
                int py = y + EDITOR_H - 2 - (int) (Math.max(0, Math.min(1, data[i])) * (EDITOR_H - 4));

                // 控制點
                gui.fill(px - 2, py - 2, px + 3, py + 3, POINT_COLOR);

                // 曲線段
                if (i > 0) {
                    drawLine(gui, prevPx, prevPy, px, py, CURVE_COLOR);
                }
                prevPx = px;
                prevPy = py;
            }
        }

        return EDITOR_H + 2;
    }

    public boolean mouseClicked(InputPort port, int x, int y,
                                 double mouseX, double mouseY) {
        float[] data = getCurveData(port);
        if (data == null) return false;

        for (int i = 0; i < data.length; i++) {
            int px = x + (int) ((float) i / (data.length - 1) * (EDITOR_W - 4)) + 2;
            int py = y + EDITOR_H - 2 - (int) (data[i] * (EDITOR_H - 4));
            if (Math.abs(mouseX - px) < 5 && Math.abs(mouseY - py) < 5) {
                dragPointIndex = i;
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(InputPort port, int x, int y,
                                 double mouseX, double mouseY) {
        if (dragPointIndex < 0) return false;
        float[] data = getCurveData(port);
        if (data == null || dragPointIndex >= data.length) return false;

        float newValue = 1.0f - (float) (mouseY - y) / EDITOR_H;
        data[dragPointIndex] = Math.max(0, Math.min(1, newValue));
        port.setLocalValue(data);
        return true;
    }

    public void mouseReleased() {
        dragPointIndex = -1;
    }

    private float[] getCurveData(InputPort port) {
        Object val = port.getRawValue();
        if (val instanceof float[] arr) return arr;
        return null;
    }

    private void drawLine(GuiGraphics gui, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int steps = 0, maxSteps = dx + dy + 1;
        while (steps++ < maxSteps) {
            gui.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }
}
