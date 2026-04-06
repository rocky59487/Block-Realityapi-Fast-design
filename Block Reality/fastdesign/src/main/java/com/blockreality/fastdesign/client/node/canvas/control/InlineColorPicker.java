package com.blockreality.fastdesign.client.node.canvas.control;

import com.blockreality.fastdesign.client.node.InputPort;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌色彩選擇器 — 設計報告 §12.1 N2-8
 *
 * 用於 COLOR 型別的輸入端口。
 * 顯示當前色塊 + 展開時的 HSV 色盤。
 */
@OnlyIn(Dist.CLIENT)
public class InlineColorPicker {

    private static final int SWATCH_SIZE = 14;
    private static final int PICKER_W = 100;
    private static final int PICKER_H = 80;
    private static final int BORDER_COLOR = 0xFF4A4A6A;

    private boolean expanded = false;

    /**
     * 渲染色塊（折疊狀態）。
     */
    public int render(GuiGraphics gui, InputPort port, int x, int y) {
        int color = port.getColor();

        // 色塊
        gui.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, color);
        gui.fill(x, y, x + SWATCH_SIZE, y + 1, BORDER_COLOR);
        gui.fill(x, y + SWATCH_SIZE - 1, x + SWATCH_SIZE, y + SWATCH_SIZE, BORDER_COLOR);
        gui.fill(x, y, x + 1, y + SWATCH_SIZE, BORDER_COLOR);
        gui.fill(x + SWATCH_SIZE - 1, y, x + SWATCH_SIZE, y + SWATCH_SIZE, BORDER_COLOR);

        // Hex 文字
        String hex = String.format("#%06X", color & 0x00FFFFFF);
        gui.drawString(Minecraft.getInstance().font, hex,
                x + SWATCH_SIZE + 4, y + 3, 0xFFAAAAAA);

        int totalH = SWATCH_SIZE + 2;

        // 展開的色盤
        if (expanded) {
            totalH += renderHSVPicker(gui, port, x, y + SWATCH_SIZE + 2);
        }

        return totalH;
    }

    /**
     * 渲染 HSV 色盤。
     */
    private int renderHSVPicker(GuiGraphics gui, InputPort port, int x, int y) {
        // 色相條（水平）
        for (int i = 0; i < PICKER_W; i++) {
            float hue = (float) i / PICKER_W;
            int rgb = hsvToRgb(hue, 1.0f, 1.0f);
            gui.fill(x + i, y, x + i + 1, y + 10, 0xFF000000 | rgb);
        }

        // 飽和度-亮度區域
        int svY = y + 14;
        for (int sy = 0; sy < PICKER_H - 14; sy++) {
            for (int sx = 0; sx < PICKER_W; sx++) {
                float s = (float) sx / PICKER_W;
                float v = 1.0f - (float) sy / (PICKER_H - 14);
                // 使用當前色相
                float hue = extractHue(port.getColor());
                int rgb = hsvToRgb(hue, s, v);
                gui.fill(x + sx, svY + sy, x + sx + 1, svY + sy + 1, 0xFF000000 | rgb);
            }
        }

        return PICKER_H;
    }

    public boolean mouseClicked(InputPort port, int x, int y,
                                 double mouseX, double mouseY) {
        // 色塊點擊 → 展開
        if (mouseX >= x && mouseX <= x + SWATCH_SIZE
                && mouseY >= y && mouseY <= y + SWATCH_SIZE) {
            expanded = !expanded;
            return true;
        }

        // 色盤點擊 → 選色
        if (expanded) {
            int pickerY = y + SWATCH_SIZE + 2;
            if (mouseX >= x && mouseX <= x + PICKER_W) {
                // 色相條
                if (mouseY >= pickerY && mouseY <= pickerY + 10) {
                    float hue = (float) (mouseX - x) / PICKER_W;
                    int currentColor = port.getColor();
                    float[] hsv = rgbToHsv(currentColor);
                    int newColor = hsvToRgb(hue, hsv[1], hsv[2]);
                    port.setLocalValue(0xFF000000 | newColor);
                    return true;
                }
                // SV 區域
                int svY = pickerY + 14;
                if (mouseY >= svY && mouseY <= svY + PICKER_H - 14) {
                    float s = (float) (mouseX - x) / PICKER_W;
                    float v = 1.0f - (float) (mouseY - svY) / (PICKER_H - 14);
                    float hue = extractHue(port.getColor());
                    int newColor = hsvToRgb(hue, s, v);
                    port.setLocalValue(0xFF000000 | newColor);
                    return true;
                }
            }
        }
        return false;
    }

    // ─── HSV 工具 ───

    private static int hsvToRgb(float h, float s, float v) {
        int hi = (int) (h * 6) % 6;
        float f = h * 6 - hi;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);

        float r, g, b;
        switch (hi) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static float[] rgbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;

        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d % 6) / 6;
            else if (max == g) h = ((b - r) / d + 2) / 6;
            else h = ((r - g) / d + 4) / 6;
            if (h < 0) h += 1;
        }
        float s = max > 0 ? d / max : 0;
        return new float[]{h, s, max};
    }

    private static float extractHue(int argb) {
        return rgbToHsv(argb)[0];
    }
}
