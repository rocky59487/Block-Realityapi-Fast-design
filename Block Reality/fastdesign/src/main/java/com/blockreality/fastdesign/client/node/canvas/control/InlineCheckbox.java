package com.blockreality.fastdesign.client.node.canvas.control;

import com.blockreality.fastdesign.client.node.InputPort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌勾選框控件 — 設計報告 §12.1 N2-8
 *
 * 用於 BOOL 型別的輸入端口。
 */
public class InlineCheckbox {

    private static final int BOX_SIZE = 10;
    private static final int BOX_BG = 0xFF0A0A14;
    private static final int BOX_BORDER = 0xFF4A4A6A;
    private static final int CHECK_COLOR = 0xFF44CC44;
    private static final int TEXT_COLOR = 0xFFDDDDDD;

    /**
     * 渲染勾選框。
     * @return 佔用高度
     */
    public int render(GuiGraphics gui, InputPort port, int x, int y, String label) {
        boolean checked = port.getBool();

        // 外框
        gui.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, BOX_BG);
        gui.fill(x, y, x + BOX_SIZE, y + 1, BOX_BORDER);
        gui.fill(x, y + BOX_SIZE - 1, x + BOX_SIZE, y + BOX_SIZE, BOX_BORDER);
        gui.fill(x, y, x + 1, y + BOX_SIZE, BOX_BORDER);
        gui.fill(x + BOX_SIZE - 1, y, x + BOX_SIZE, y + BOX_SIZE, BOX_BORDER);

        // 勾選標記
        if (checked) {
            gui.fill(x + 2, y + 2, x + BOX_SIZE - 2, y + BOX_SIZE - 2, CHECK_COLOR);
        }

        // 標籤
        gui.drawString(Minecraft.getInstance().font, label, x + BOX_SIZE + 4, y + 1, TEXT_COLOR);

        return BOX_SIZE + 2;
    }

    /**
     * 點擊切換。
     */
    public boolean mouseClicked(InputPort port, int x, int y,
                                 double mouseX, double mouseY) {
        if (mouseX >= x && mouseX <= x + BOX_SIZE
                && mouseY >= y && mouseY <= y + BOX_SIZE) {
            port.setLocalValue(!port.getBool());
            return true;
        }
        return false;
    }
}
