package com.blockreality.fastdesign.client.node.canvas.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

/**
 * 內嵌合成配方網格 — 設計報告 B5-7 RecipeAssigner
 *
 * 3×3 的合成網格編輯器，用於定義自訂方塊的合成配方。
 */
public class InlineRecipeGrid {

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int CELL_SIZE = 18;
    private static final int GAP = 2;
    private static final int BG_COLOR = 0xFF1A1A2A;
    private static final int CELL_EMPTY = 0xFF0A0A14;
    private static final int CELL_FILLED = 0xFF2A4A3A;
    private static final int BORDER_COLOR = 0xFF3A3A5A;
    private static final int TEXT_COLOR = 0xFFDDDDDD;

    @Nullable
    private final String[] slots = new String[GRID_COLS * GRID_ROWS];
    private int selectedSlot = -1;

    /**
     * 渲染合成網格。
     */
    public int render(GuiGraphics gui, int x, int y) {
        int totalW = GRID_COLS * (CELL_SIZE + GAP) - GAP;
        int totalH = GRID_ROWS * (CELL_SIZE + GAP) - GAP;

        // 背景
        gui.fill(x - 2, y - 2, x + totalW + 2, y + totalH + 2, BG_COLOR);

        // 格子
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int cx = x + col * (CELL_SIZE + GAP);
                int cy = y + row * (CELL_SIZE + GAP);

                boolean filled = slots[idx] != null;
                boolean selected = (idx == selectedSlot);

                // 背景
                gui.fill(cx, cy, cx + CELL_SIZE, cy + CELL_SIZE,
                        filled ? CELL_FILLED : CELL_EMPTY);

                // 選中邊框
                if (selected) {
                    gui.fill(cx, cy, cx + CELL_SIZE, cy + 1, 0xFF00CCCC);
                    gui.fill(cx, cy + CELL_SIZE - 1, cx + CELL_SIZE, cy + CELL_SIZE, 0xFF00CCCC);
                    gui.fill(cx, cy, cx + 1, cy + CELL_SIZE, 0xFF00CCCC);
                    gui.fill(cx + CELL_SIZE - 1, cy, cx + CELL_SIZE, cy + CELL_SIZE, 0xFF00CCCC);
                } else {
                    gui.fill(cx, cy, cx + CELL_SIZE, cy + 1, BORDER_COLOR);
                    gui.fill(cx, cy + CELL_SIZE - 1, cx + CELL_SIZE, cy + CELL_SIZE, BORDER_COLOR);
                    gui.fill(cx, cy, cx + 1, cy + CELL_SIZE, BORDER_COLOR);
                    gui.fill(cx + CELL_SIZE - 1, cy, cx + CELL_SIZE, cy + CELL_SIZE, BORDER_COLOR);
                }

                // 物品縮寫
                if (filled) {
                    String abbrev = abbreviate(slots[idx]);
                    gui.drawString(Minecraft.getInstance().font, abbrev,
                            cx + 2, cy + 5, TEXT_COLOR);
                }
            }
        }

        // 箭頭 + 輸出
        int arrowX = x + totalW + 6;
        int arrowY = y + totalH / 2 - 4;
        gui.drawString(Minecraft.getInstance().font, "\u2192", arrowX, arrowY, 0xFFAAAAAA);

        return totalH + 4;
    }

    /**
     * 點擊選取格子。
     */
    public boolean mouseClicked(int x, int y, double mouseX, double mouseY) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = x + col * (CELL_SIZE + GAP);
                int cy = y + row * (CELL_SIZE + GAP);
                if (mouseX >= cx && mouseX <= cx + CELL_SIZE
                        && mouseY >= cy && mouseY <= cy + CELL_SIZE) {
                    selectedSlot = row * GRID_COLS + col;
                    return true;
                }
            }
        }
        return false;
    }

    // ─── 格子操作 ───

    public void setSlot(int index, @Nullable String blockId) {
        if (index >= 0 && index < slots.length) {
            slots[index] = blockId;
        }
    }

    public void setSelectedSlotBlock(@Nullable String blockId) {
        if (selectedSlot >= 0) {
            slots[selectedSlot] = blockId;
        }
    }

    public void clearSlot(int index) {
        setSlot(index, null);
    }

    @Nullable
    public String getSlot(int index) {
        return (index >= 0 && index < slots.length) ? slots[index] : null;
    }

    public String[] getAllSlots() {
        return slots.clone();
    }

    /**
     * 生成配方 pattern（如 ["ABA", " A ", "   "]）。
     */
    public String[] toPattern() {
        String[] pattern = new String[GRID_ROWS];
        for (int row = 0; row < GRID_ROWS; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < GRID_COLS; col++) {
                String slot = slots[row * GRID_COLS + col];
                sb.append(slot != null ? getKey(slot) : ' ');
            }
            pattern[row] = sb.toString();
        }
        return pattern;
    }

    private char getKey(String blockId) {
        // 簡化：用首字母大寫
        if (blockId.contains(":")) blockId = blockId.substring(blockId.indexOf(':') + 1);
        return Character.toUpperCase(blockId.charAt(0));
    }

    private String abbreviate(String blockId) {
        if (blockId == null) return "";
        if (blockId.contains(":")) blockId = blockId.substring(blockId.indexOf(':') + 1);
        if (blockId.length() <= 2) return blockId;
        return blockId.substring(0, 2);
    }
}
