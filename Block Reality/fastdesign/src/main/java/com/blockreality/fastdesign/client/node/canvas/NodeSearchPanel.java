package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.NodeColor;
import com.blockreality.fastdesign.client.node.NodeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.*;

/**
 * 節點搜尋面板 — 設計報告 §10.4, §12.1 N2-5
 *
 * Tab 或雙擊空白處彈出。
 * 支援英文/中文名稱模糊搜尋、類別過濾。
 *
 * ★ UI-2 (2025-04): 加入分類樹狀選單。
 *   - 無搜尋字串時顯示可摺疊的分類標題（預設摺疊）
 *   - 有搜尋字串時恢復平鋪清單
 *   - 分類名稱中文化（英文 key → 中文顯示）
 */
public class NodeSearchPanel {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 320;
    private static final int ITEM_HEIGHT    = 18;
    private static final int HEADER_HEIGHT  = 20;
    /** ★ UI/UX: Create/Grasshopper 原生與溫和的背景 */
    private static final int BG_COLOR       = 0xF0202022; // 微透明深灰
    private static final int BORDER_COLOR   = 0xFF353538;
    private static final int HIGHLIGHT_COLOR= 0xFF4A4A4F; // 柔和選中亮灰
    private static final int HEADER_COLOR   = 0xFF2A2A2D; // 標題欄微亮
    private static final int HEADER_HL_COLOR= 0xFF3F3F42;
    private static final int TEXT_COLOR     = 0xFFF0F0F0;
    private static final int DIM_COLOR      = 0xFFA0A0A5;
    private static final int ACCENT_COLOR   = 0xFFFFF1A5; // 草蜢風格亮黃

    // ★ UI-2: 類別名稱中文化對照表
    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    static {
        CATEGORY_LABELS.put("render",  "[視覺與渲染]");
        CATEGORY_LABELS.put("postfx",  "[後製效果]");
        CATEGORY_LABELS.put("math",    "[數學運算]");
        CATEGORY_LABELS.put("logic",   "[控制邏輯]");
        CATEGORY_LABELS.put("input",   "[輸入控制]");
        CATEGORY_LABELS.put("core",    "[核心系統]");
        CATEGORY_LABELS.put("material","[材料系統]");
        CATEGORY_LABELS.put("physics", "[物理計算]");
        CATEGORY_LABELS.put("output",  "[匯出輸出]");
        CATEGORY_LABELS.put("tool",    "[工具輔助]");
    }

    private final NodeCanvasScreen parent;
    private float screenX, screenY;
    private float targetCursorX, targetCursorY;
    private boolean visible = true;
    private String query = "";
    private int scrollOffset = 0;
    private int selectedIndex = 0;

    // 平鋪搜尋結果（有 query 時使用）
    private List<NodeRegistry.NodeEntry> results = new ArrayList<>();

    // 分類樹狀結構（無 query 時使用）
    private Map<String, Boolean> categoryExpanded = new LinkedHashMap<>();  // key → expanded
    private record ListItem(boolean isHeader, String categoryKey, NodeRegistry.NodeEntry entry) {}
    private List<ListItem> treeItems = new ArrayList<>();
    // 每個分類的節點數量（buildTreeItems 時快取，避免 render 迴圈呼叫 byCategory()）
    private Map<String, Integer> categoryCounts = new LinkedHashMap<>();

    public NodeSearchPanel(NodeCanvasScreen parent, float screenX, float screenY) {
        this.parent = parent;
        // ★ ICReM-9: 面板邊界檢查，防止超出螢幕
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        this.targetCursorX = screenX;
        this.targetCursorY = screenY;
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

        // 陰影
        gui.fill(x + 4, y + 4, x + PANEL_W + 4, y + PANEL_H + 4, 0x30000000);

        // 背景
        gui.fill(x, y, x + PANEL_W, y + PANEL_H, BG_COLOR);
        // 邊框
        gui.fill(x, y, x + PANEL_W, y + 1, BORDER_COLOR);
        gui.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, BORDER_COLOR);
        gui.fill(x, y, x + 1, y + PANEL_H, BORDER_COLOR);
        gui.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, BORDER_COLOR);

        Font font = Minecraft.getInstance().font;

        // 搜尋框背景 (圓角效果)
        gui.fill(x + 4, y + 4, x + PANEL_W - 4, y + 20, 0xFF18181A);
        gui.fill(x + 4, y + 4, x + PANEL_W - 4, y + 5, BORDER_COLOR);
        gui.fill(x + 4, y + 19, x + PANEL_W - 4, y + 20, BORDER_COLOR);
        gui.fill(x + 4, y + 4, x + 5, y + 20, BORDER_COLOR);
        gui.fill(x + PANEL_W - 5, y + 4, x + PANEL_W - 4, y + 20, BORDER_COLOR);

        String displayQuery = query.isEmpty() ? "\u00A77\u00A7o\u641C\u5C0B\u7BC0\u9EDE..." : query;
        gui.drawString(font, displayQuery, x + 8, y + 8, query.isEmpty() ? DIM_COLOR : TEXT_COLOR);

        // ★ UI-2: 根據 query 決定顯示模式
        if (query.isEmpty()) {
            renderTree(gui, font, x, y, mouseX, mouseY);
        } else {
            renderFlatList(gui, font, x, y, mouseX, mouseY);
        }
    }

    /**
     * ★ UI-2: 分類樹狀模式渲染（query 為空時）。
     * 標題行：深色底 + ▶/▼ 箭頭 + 中文分類名 + 右側節點數
     * 子節點行：縮排 + 類別色標 + 英文名 + 右側中文名
     */
    private void renderTree(GuiGraphics gui, Font font, int x, int y, int mouseX, int mouseY) {
        int listY = y + 24;
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        int totalEntries = 0;

        for (int i = scrollOffset; i < treeItems.size() && i - scrollOffset < maxVisible; i++) {
            ListItem item = treeItems.get(i);
            int itemY = listY + (i - scrollOffset) * ITEM_HEIGHT;

            if (item.isHeader()) {
                // 分類標題行
                boolean hl = (i == selectedIndex);
                gui.fill(x + 2, itemY, x + PANEL_W - 2, itemY + ITEM_HEIGHT,
                         hl ? HEADER_HL_COLOR : HEADER_COLOR);

                // ▶ / ▼ 展開指示符
                boolean expanded = Boolean.TRUE.equals(categoryExpanded.get(item.categoryKey()));
                String arrow = expanded ? "\u25BC " : "\u25BA ";
                gui.drawString(font, arrow, x + 6, itemY + 4, ACCENT_COLOR);

                // 中文分類標籤
                String label = CATEGORY_LABELS.getOrDefault(item.categoryKey(),
                               "[" + item.categoryKey() + "]");
                gui.drawString(font, label, x + 18, itemY + 4, TEXT_COLOR);

                // 右側節點數量（使用 buildTreeItems 快取，不重新 groupBy）
                int cnt = categoryCounts.getOrDefault(item.categoryKey(), 0);
                String cntStr = cnt + " \u500B";  // "N 個"
                int cw = font.width(cntStr);
                gui.drawString(font, cntStr, x + PANEL_W - cw - 8, itemY + 4, DIM_COLOR);

            } else {
                // 節點條目行（展開分類下的子項）
                NodeRegistry.NodeEntry entry = item.entry();
                totalEntries++;

                if (i == selectedIndex) {
                    gui.fill(x + 2, itemY, x + PANEL_W - 2, itemY + ITEM_HEIGHT, HIGHLIGHT_COLOR);
                }

                // 縮排 + 類別色標
                NodeColor nc = NodeColor.fromCategory(entry.category());
                gui.fill(x + 18, itemY + 3, x + 22, itemY + ITEM_HEIGHT - 3, nc.argb());

                gui.drawString(font, entry.displayNameEN(), x + 26, itemY + 4, TEXT_COLOR);

                String cn = entry.displayNameCN();
                if (cn != null && !cn.isEmpty()) {
                    int cnW = font.width(cn);
                    gui.drawString(font, cn, x + PANEL_W - cnW - 8, itemY + 4, DIM_COLOR);
                }
            }
        }

        // 狀態列：已展開的節點數 / 總節點數
        int totalAll = categoryCounts.values().stream().mapToInt(Integer::intValue).sum();
        String countStr = results.size() + " \u7BC0\u9EDE";  // results 在 query 空時 = 全部
        gui.drawString(font, countStr, x + 4, y + PANEL_H - 14, DIM_COLOR);
    }

    /**
     * 平鋪搜尋清單渲染（query 不為空時）。
     * 與原始邏輯相同。
     */
    private void renderFlatList(GuiGraphics gui, Font font, int x, int y, int mouseX, int mouseY) {
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
            int listSize = query.isEmpty() ? treeItems.size() : results.size();
            selectedIndex = Math.min(selectedIndex + 1, listSize - 1);
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

        int listY = y + 24;
        if (mouseY > listY) {
            int idx = scrollOffset + (int) (mouseY - listY) / ITEM_HEIGHT;

            if (query.isEmpty()) {
                // ★ UI-2: 樹狀模式 — 標題點擊展開/摺疊，子項點擊放置節點
                if (idx >= 0 && idx < treeItems.size()) {
                    selectedIndex = idx;
                    ListItem item = treeItems.get(idx);
                    if (item.isHeader()) {
                        boolean cur = Boolean.TRUE.equals(categoryExpanded.get(item.categoryKey()));
                        categoryExpanded.put(item.categoryKey(), !cur);
                        buildTreeItems();
                    } else {
                        confirmSelection();
                    }
                    return true;
                }
            } else {
                // 平鋪模式 — 原始行為
                if (idx >= 0 && idx < results.size()) {
                    selectedIndex = idx;
                    confirmSelection();
                    return true;
                }
            }
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int listSize = query.isEmpty() ? treeItems.size() : results.size();
        scrollOffset = Math.max(0, scrollOffset - (int) delta);
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        scrollOffset = Math.min(scrollOffset, Math.max(0, listSize - maxVisible));
        return true;
    }

    // ─── 內部 ───

    private void refreshResults() {
        results = NodeRegistry.search(query);
        if (selectedIndex >= results.size()) {
            selectedIndex = Math.max(0, results.size() - 1);
        }
        // ★ UI-2: 無 query 時重建樹狀清單；有 query 時清空（不使用）
        if (query.isEmpty()) {
            buildTreeItems();
        } else {
            treeItems.clear();
        }
    }

    /**
     * ★ UI-2: 重建分類樹狀清單。
     *
     * 依 CATEGORY_LABELS 定義的順序排列已知類別，附加未知類別至末尾。
     * 每個類別加入一個 Header ListItem；若已展開，其下緊接所有子節點 ListItem。
     * 同時更新 categoryCounts 快取供 render 使用。
     */
    private void buildTreeItems() {
        treeItems.clear();
        categoryCounts.clear();

        Map<String, List<NodeRegistry.NodeEntry>> byCategory = NodeRegistry.byCategory();

        // 以 CATEGORY_LABELS 順序為主，再附加未識別類別
        List<String> orderedKeys = new ArrayList<>(CATEGORY_LABELS.keySet());
        for (String key : byCategory.keySet()) {
            if (!orderedKeys.contains(key)) orderedKeys.add(key);
        }

        for (String catKey : orderedKeys) {
            List<NodeRegistry.NodeEntry> entries = byCategory.get(catKey);
            if (entries == null || entries.isEmpty()) continue;

            // 快取節點數量
            categoryCounts.put(catKey, entries.size());

            // 初始化為摺疊（僅在第一次見到此類別時設定）
            categoryExpanded.putIfAbsent(catKey, false);

            // 加入標題行
            treeItems.add(new ListItem(true, catKey, null));

            // 若展開，加入所有子節點行
            if (Boolean.TRUE.equals(categoryExpanded.get(catKey))) {
                for (NodeRegistry.NodeEntry e : entries) {
                    treeItems.add(new ListItem(false, catKey, e));
                }
            }
        }

        // selectedIndex 邊界修正
        int listSize = treeItems.size();
        if (selectedIndex >= listSize && listSize > 0) {
            selectedIndex = listSize - 1;
        } else if (listSize == 0) {
            selectedIndex = 0;
        }
    }

    private void ensureVisible() {
        int maxVisible = (PANEL_H - 28) / ITEM_HEIGHT;
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
    }

    private void confirmSelection() {
        if (query.isEmpty()) {
            // ★ UI-2: 樹狀模式 — 僅對節點條目（非標題）生效
            if (selectedIndex >= 0 && selectedIndex < treeItems.size()) {
                ListItem item = treeItems.get(selectedIndex);
                if (!item.isHeader() && item.entry() != null) {
                    parent.addNodeFromSearch(item.entry().typeId(), targetCursorX, targetCursorY);
                }
            }
        } else {
            // 平鋪模式 — 原始行為
            if (selectedIndex >= 0 && selectedIndex < results.size()) {
                NodeRegistry.NodeEntry entry = results.get(selectedIndex);
                parent.addNodeFromSearch(entry.typeId(), targetCursorX, targetCursorY);
            }
        }
    }
}
