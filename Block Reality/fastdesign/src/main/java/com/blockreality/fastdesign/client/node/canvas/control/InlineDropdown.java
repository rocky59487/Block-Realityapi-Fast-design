package com.blockreality.fastdesign.client.node.canvas.control;

import com.blockreality.fastdesign.client.node.InputPort;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 內嵌下拉選單控件 — 設計報告 §12.1 N2-8
 *
 * 用於 ENUM 型別的輸入端口。
 * 點擊展開選項列表。
 */
@OnlyIn(Dist.CLIENT)
public class InlineDropdown {

    private static final int DROP_HEIGHT = 14;
    private static final int DROP_BG = 0xFF0A0A14;
    private static final int DROP_BORDER = 0xFF4A4A6A;
    private static final int DROP_HOVER = 0xFF2A4A6A;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int ARROW_COLOR = 0xFF888888;

    private boolean expanded = false;
    private String[] options;
    private int selectedIndex = 0;

    public InlineDropdown(String[] options) {
        this.options = options != null ? options : new String[0];
    }

    /**
     * 渲染下拉選單。
     * @return 佔用高度
     */
    public int render(GuiGraphics gui, InputPort port, int x, int y, int width) {
        // 主框
        gui.fill(x, y, x + width, y + DROP_HEIGHT, DROP_BG);
        gui.fill(x, y, x + width, y + 1, DROP_BORDER);
        gui.fill(x, y + DROP_HEIGHT - 1, x + width, y + DROP_HEIGHT, DROP_BORDER);
        gui.fill(x, y, x + 1, y + DROP_HEIGHT, DROP_BORDER);
        gui.fill(x + width - 1, y, x + width, y + DROP_HEIGHT, DROP_BORDER);

        // 當前值
        String current = getCurrentLabel(port);
        gui.drawString(Minecraft.getInstance().font, current, x + 4, y + 3, TEXT_COLOR);

        // 下拉箭頭
        gui.drawString(Minecraft.getInstance().font, expanded ? "\u25B2" : "\u25BC",
                x + width - 10, y + 3, ARROW_COLOR);

        int totalHeight = DROP_HEIGHT + 2;

        // 展開的選項列表
        if (expanded && options.length > 0) {
            int listY = y + DROP_HEIGHT;
            for (int i = 0; i < options.length; i++) {
                int itemBg = (i == selectedIndex) ? DROP_HOVER : DROP_BG;
                gui.fill(x, listY, x + width, listY + DROP_HEIGHT, itemBg);
                gui.fill(x, listY, x + 1, listY + DROP_HEIGHT, DROP_BORDER);
                gui.fill(x + width - 1, listY, x + width, listY + DROP_HEIGHT, DROP_BORDER);
                gui.drawString(Minecraft.getInstance().font, options[i],
                        x + 4, listY + 3, TEXT_COLOR);
                listY += DROP_HEIGHT;
            }
            gui.fill(x, listY - 1, x + width, listY, DROP_BORDER);
            totalHeight += options.length * DROP_HEIGHT;
        }

        return totalHeight;
    }

    /**
     * 點擊處理。
     */
    public boolean mouseClicked(InputPort port, int x, int y, int width,
                                 double mouseX, double mouseY) {
        // 主框點擊 → 切換展開
        if (mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + DROP_HEIGHT) {
            expanded = !expanded;
            return true;
        }

        // 選項列表點擊
        if (expanded) {
            int listY = y + DROP_HEIGHT;
            for (int i = 0; i < options.length; i++) {
                if (mouseX >= x && mouseX <= x + width
                        && mouseY >= listY && mouseY <= listY + DROP_HEIGHT) {
                    selectedIndex = i;
                    port.setLocalValue(options[i]);
                    expanded = false;
                    return true;
                }
                listY += DROP_HEIGHT;
            }
            expanded = false;
        }
        return false;
    }

    public void setOptions(String[] options) {
        this.options = options != null ? options : new String[0];
    }

    private String getCurrentLabel(InputPort port) {
        Object val = port.getRawValue();
        if (val instanceof Enum<?> e) return e.name();
        if (val instanceof String s) return s;
        return val != null ? val.toString() : "---";
    }
}
