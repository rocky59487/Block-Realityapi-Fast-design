package com.blockreality.fastdesign.client.node.canvas.control;

import com.blockreality.fastdesign.client.node.InputPort;
import com.blockreality.fastdesign.client.node.PortType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌滑桿控件 — 設計報告 §3.4, §12.1 N2-8
 *
 * 用於 FLOAT 和 INT 型別的輸入端口。
 * 在節點內部渲染一個水平滑桿 + 數值顯示。
 */
public class InlineSlider {

    private static final int BAR_BG = 0xFF0A0A14;
    private static final int BAR_FILL = 0xFF2A5A8A;
    private static final int BAR_HANDLE = 0xFFCCCCCC;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int BAR_HEIGHT = 10;

    private boolean dragging = false;

    /**
     * 渲染滑桿。
     * @return 佔用高度
     */
    public int render(GuiGraphics gui, InputPort port, int x, int y, int width) {
        float min = port.min();
        float max = port.max();
        if (Float.isInfinite(min)) min = 0;
        if (Float.isInfinite(max)) max = 100;

        float value = port.getFloat();
        float ratio = (max > min) ? (value - min) / (max - min) : 0;
        ratio = Math.max(0, Math.min(1, ratio));

        // 背景
        gui.fill(x, y, x + width, y + BAR_HEIGHT, BAR_BG);

        // 填充
        int fillW = (int) (width * ratio);
        gui.fill(x, y, x + fillW, y + BAR_HEIGHT, BAR_FILL);

        // 手柄
        int handleX = x + fillW - 1;
        gui.fill(handleX, y, handleX + 3, y + BAR_HEIGHT, BAR_HANDLE);

        // 數值文字
        String text;
        if (port.type() == PortType.INT) {
            text = String.valueOf(port.getInt());
        } else {
            text = String.format("%.2f", value);
        }
        gui.drawString(Minecraft.getInstance().font, text,
                x + width - Minecraft.getInstance().font.width(text) - 2,
                y + 1, TEXT_COLOR);

        return BAR_HEIGHT + 2;
    }

    /**
     * 處理滑桿拖曳。
     * @return true 如果事件被消耗
     */
    public boolean mouseClicked(InputPort port, int x, int y, int width,
                                 double mouseX, double mouseY) {
        if (mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + BAR_HEIGHT) {
            dragging = true;
            updateValue(port, x, width, mouseX);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(InputPort port, int x, int width, double mouseX) {
        if (dragging) {
            updateValue(port, x, width, mouseX);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private void updateValue(InputPort port, int x, int width, double mouseX) {
        float min = port.min();
        float max = port.max();
        if (Float.isInfinite(min)) min = 0;
        if (Float.isInfinite(max)) max = 100;

        float ratio = (float) Math.max(0, Math.min(1, (mouseX - x) / width));
        float value = min + ratio * (max - min);

        // 步進量化
        float step = port.step();
        if (step > 0 && !Float.isInfinite(step)) {
            value = Math.round(value / step) * step;
        }

        if (port.type() == PortType.INT) {
            port.setLocalValue(Math.round(value));
        } else {
            port.setLocalValue(value);
        }
    }
}
