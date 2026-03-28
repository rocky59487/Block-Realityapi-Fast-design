package com.blockreality.fastdesign.client.node.canvas.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 內嵌方塊選取器 — 設計報告 B5-1 VanillaBlockPicker
 *
 * 用於在節點內部搜尋並選取 Minecraft 方塊。
 * 支援文字搜尋和分類瀏覽。
 */
public class InlineBlockPicker {

    private static final int PICKER_W = 160;
    private static final int PICKER_H = 120;
    private static final int BG_COLOR = 0xFF0A0A14;
    private static final int BORDER_COLOR = 0xFF3A3A5A;
    private static final int ITEM_HEIGHT = 14;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int HIGHLIGHT_COLOR = 0xFF2A4A6A;

    private boolean expanded = false;
    private String query = "";
    @Nullable private ResourceLocation selected;
    private List<ResourceLocation> filteredBlocks = new ArrayList<>();
    private int scrollOffset = 0;
    private int hoverIndex = -1;

    // 常用方塊快速清單（正式版應從 Registry 查詢）
    private static final String[] COMMON_BLOCKS = {
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks",
            "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block",
            "minecraft:bricks", "minecraft:sandstone", "minecraft:red_sandstone",
            "minecraft:glass", "minecraft:white_concrete", "minecraft:obsidian",
            "minecraft:bedrock", "minecraft:oak_log", "minecraft:andesite"
    };

    public InlineBlockPicker() {
        refreshFilter();
    }

    /**
     * 渲染選取器（折疊狀態顯示當前選取）。
     */
    public int render(GuiGraphics gui, int x, int y) {
        // 當前選取顯示
        String display = selected != null ? selected.toString() : "Click to select...";
        gui.fill(x, y, x + PICKER_W, y + ITEM_HEIGHT, BG_COLOR);
        gui.fill(x, y, x + PICKER_W, y + 1, BORDER_COLOR);
        gui.fill(x, y + ITEM_HEIGHT - 1, x + PICKER_W, y + ITEM_HEIGHT, BORDER_COLOR);
        gui.drawString(Minecraft.getInstance().font, display, x + 4, y + 3, TEXT_COLOR);

        int totalH = ITEM_HEIGHT + 2;

        if (expanded) {
            // 搜尋框
            gui.fill(x, y + ITEM_HEIGHT, x + PICKER_W, y + ITEM_HEIGHT * 2, 0xFF060610);
            String searchDisplay = query.isEmpty() ? "\u00A77Search..." : query;
            gui.drawString(Minecraft.getInstance().font, searchDisplay,
                    x + 4, y + ITEM_HEIGHT + 3, query.isEmpty() ? 0xFF666666 : TEXT_COLOR);

            // 結果列表
            int listY = y + ITEM_HEIGHT * 2;
            int maxItems = (PICKER_H - ITEM_HEIGHT * 2) / ITEM_HEIGHT;
            for (int i = scrollOffset; i < filteredBlocks.size() && i - scrollOffset < maxItems; i++) {
                int itemY = listY + (i - scrollOffset) * ITEM_HEIGHT;
                int bg = (i == hoverIndex) ? HIGHLIGHT_COLOR : BG_COLOR;
                gui.fill(x, itemY, x + PICKER_W, itemY + ITEM_HEIGHT, bg);
                gui.drawString(Minecraft.getInstance().font,
                        filteredBlocks.get(i).toString(), x + 4, itemY + 3, TEXT_COLOR);
            }
            totalH += PICKER_H;
        }

        return totalH;
    }

    public boolean mouseClicked(int x, int y, double mouseX, double mouseY) {
        // 主框點擊
        if (mouseX >= x && mouseX <= x + PICKER_W
                && mouseY >= y && mouseY <= y + ITEM_HEIGHT) {
            expanded = !expanded;
            return true;
        }

        if (expanded) {
            // 列表項目點擊
            int listY = y + ITEM_HEIGHT * 2;
            int maxItems = (PICKER_H - ITEM_HEIGHT * 2) / ITEM_HEIGHT;
            for (int i = scrollOffset; i < filteredBlocks.size() && i - scrollOffset < maxItems; i++) {
                int itemY = listY + (i - scrollOffset) * ITEM_HEIGHT;
                if (mouseX >= x && mouseX <= x + PICKER_W
                        && mouseY >= itemY && mouseY <= itemY + ITEM_HEIGHT) {
                    selected = filteredBlocks.get(i);
                    expanded = false;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (expanded && c >= 32) {
            query += c;
            refreshFilter();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode) {
        if (expanded && keyCode == 259 && !query.isEmpty()) { // Backspace
            query = query.substring(0, query.length() - 1);
            refreshFilter();
            return true;
        }
        return false;
    }

    @Nullable
    public ResourceLocation getSelected() { return selected; }

    public void setSelected(@Nullable ResourceLocation block) { this.selected = block; }

    private void refreshFilter() {
        filteredBlocks.clear();
        String lower = query.toLowerCase(Locale.ROOT);
        for (String blockStr : COMMON_BLOCKS) {
            if (lower.isEmpty() || blockStr.toLowerCase(Locale.ROOT).contains(lower)) {
                filteredBlocks.add(new ResourceLocation(blockStr));
            }
        }
    }
}
