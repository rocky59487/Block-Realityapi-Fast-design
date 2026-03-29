package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.NodeColor;
import com.blockreality.fastdesign.client.node.NodeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 節點搜尋面板 — 設計報告 §10.4, §12.1 N2-5
 *
 * Tab 或雙擊空白處彈出。
 * 支援英文/中文名稱模糊搜尋、類別過濾。
 */
public class NodeSearchPanel {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 320;
    private static final int ITEM_HEIGHT = 18;
    private static final int BG_COLOR = 0xEE1A1A2E;
    private static final int BORDER_COLOR = 0xFF3A3A5A;
    private static final int HIGHLIGHT_COLOR = 0xFF2A4A6A;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int DIM_COLOR = 0xFF888888;

    private final NodeCanvasScreen parent;
    private float screenX, screenY;
    private boolean visible = true;
    private String query = "";
    private int scrollOffset = 0;
    private int selectedIndex = 0;
    private List<NodeRegistry.NodeEntry> results = new ArrayList<>();

    public NodeSearchPanel(NodeCanvasScreen parent, float screenX, float screenY) {
        this.parent = parent;
        // ★ ICReM-9: 面板邊界檢查，防止超出螢幕
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        this.screenX = Math.max(4, Math.min(screenX - PANEL_W / 2.0f, screenW - PANEL_W - 4));
        this.screenY = Math.max(4, Math.min(screenY, screenH - PANEL_H - 4));
        refreshResults();
    }

    public boolean isVisible() { return visible; }

    public void close() { visible = false; }

    // ─── 渲染 ───

    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int x = (int) screenX, y = (int) screenY;

        // 背景
        gui.fill(x, y, x + PANEL_W, y + PANEL_H, BG_COLOR);
        // 邊框
        gui.fill(x, y, x + PANEL_W, y + 1, BORDER_COLOR);
        gui.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, BORDER_COLOR);
        gui.fill(x, y, x + 1, y + PANEL_H, BORDER_COLOR);
        gui.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, BORDER_COLOR);

        Font font = Minecraft.getInstance().font;

        // 搜尋框
        gui.fill(x + 4, y + 4, x + PANEL_W - 4, y + 20, 0xFF0A0A1A);
        String displayQuery = query.isEmpty() ? "\u00A77\u00A7o\u641C\u5C0B\u7BC0\u9EDE..." : query;
        gui.drawString(font, displayQuery, x + 8, y + 8, query.isEmpty() ? DIM_COLOR : TEXT_COLOR);

        // 結果列表
        int listY = y + 24;
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        for (int i = scrollOffset; i < results.size() && i - scrollOffset < maxVisible; i++) {
            NodeRegistry.NodeEntry entry = results.get(i);
            int itemY = listY + (i - scrollOffset) * ITEM_HEIGHT;

            // 高亮選中項
            if (i == selectedIndex) {
                gui.fill(x + 2, itemY, x + PANEL_W - 2, itemY + ITEM_HEIGHT, HIGHLIGHT_COLOR);
            }

            // 類別色標
            NodeColor nc = NodeColor.fromCategory(entry.category());
            gui.fill(x + 6, itemY + 3, x + 10, itemY + ITEM_HEIGHT - 3, nc.argb());

            // 名稱
            gui.drawString(font, entry.displayNameEN(), x + 14, itemY + 4, TEXT_COLOR);

            // 中文名（右側）
            String cn = entry.displayNameCN();
            if (cn != null && !cn.isEmpty()) {
                int cnW = font.width(cn);
                gui.drawString(font, cn, x + PANEL_W - cnW - 8, itemY + 4, DIM_COLOR);
            }
        }

        // 結果計數
        String countStr = results.size() + " \u7BC0\u9EDE";
        gui.drawString(font, countStr, x + 4, y + PANEL_H - 14, DIM_COLOR);
    }

    // ─── 輸入 ───

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 259) { // Backspace
            if (!query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
                refreshResults();
            }
            return true;
        }
        if (keyCode == 264) { // Down
            selectedIndex = Math.min(selectedIndex + 1, results.size() - 1);
            ensureVisible();
            return true;
        }
        if (keyCode == 265) { // Up
            selectedIndex = Math.max(selectedIndex - 1, 0);
            ensureVisible();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
            confirmSelection();
            return true;
        }
        if (keyCode == 256) { // Escape
            close();
            return true;
        }
        return false;
    }

    public boolean charTyped(char c, int modifiers) {
        // ★ ICReM-9: 支援 CJK 字元輸入（IME 中文/日文/韓文）
        // Character.isDefined 涵蓋所有 Unicode 可列印字元
        if (!Character.isISOControl(c) && Character.isDefined(c)) {
            query += c;
            selectedIndex = 0;
            scrollOffset = 0;
            refreshResults();
            return true;
        }
        return false;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        int x = (int) screenX, y = (int) screenY;

        // 面板外點擊 → 關閉
        if (mouseX < x || mouseX > x + PANEL_W || mouseY < y || mouseY > y + PANEL_H) {
            close();
            return false;
        }

        // 點擊結果列表
        int listY = y + 24;
        if (mouseY > listY) {
            int idx = scrollOffset + (int) (mouseY - listY) / ITEM_HEIGHT;
            if (idx >= 0 && idx < results.size()) {
                selectedIndex = idx;
                confirmSelection();
                return true;
            }
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset = Math.max(0, scrollOffset - (int) delta);
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        scrollOffset = Math.min(scrollOffset, Math.max(0, results.size() - maxVisible));
        return true;
    }

    // ─── 內部 ───

    private void refreshResults() {
        results = NodeRegistry.search(query);
        if (selectedIndex >= results.size()) {
            selectedIndex = Math.max(0, results.size() - 1);
        }
    }

    private void ensureVisible() {
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
    }

    private void confirmSelection() {
        if (selectedIndex >= 0 && selectedIndex < results.size()) {
            NodeRegistry.NodeEntry entry = results.get(selectedIndex);
            parent.addNodeFromSearch(entry.typeId(), screenX + PANEL_W / 2.0f, screenY);
        }
    }
}
