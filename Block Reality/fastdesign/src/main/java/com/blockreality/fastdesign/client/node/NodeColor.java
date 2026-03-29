package com.blockreality.fastdesign.client.node;

/**
 * 節點類別色 — 設計報告 §10.2
 *
 * 每個類別對應一個主色，用於節點 header 背景和搜尋面板圖標。
 */
public enum NodeColor {

    RENDER   ("render",   0xFF2196F3, "渲染管線", "Render Pipeline"),
    MATERIAL ("material", 0xFF4CAF50, "材料與方塊", "Materials & Blocks"),
    BLENDING ("blending", 0xFF00CC88, "材質調配", "Material Blending"),
    PHYSICS  ("physics",  0xFFFF9800, "物理計算", "Physics"),
    TOOL     ("tool",     0xFF9C27B0, "工具與 UI", "Tools & UI"),
    OUTPUT   ("output",   0xFF9E9E9E, "輸出匯出", "Output & Export");

    private final String category;
    private final int argb;
    private final String displayNameCN;
    private final String displayNameEN;

    NodeColor(String category, int argb, String displayNameCN, String displayNameEN) {
        this.category = category;
        this.argb = argb;
        this.displayNameCN = displayNameCN;
        this.displayNameEN = displayNameEN;
    }

    public String category()       { return category; }
    public int argb()              { return argb; }
    public String displayNameCN()  { return displayNameCN; }
    public String displayNameEN()  { return displayNameEN; }

    /** Header 背景色（半透明） */
    public int headerColor() {
        return (0xCC << 24) | (argb & 0x00FFFFFF);
    }

    /** Header 背景色（選中高亮） */
    public int headerHighlightColor() {
        return (0xEE << 24) | (argb & 0x00FFFFFF);
    }

    /** 從 category 字串反查。 */
    public static NodeColor fromCategory(String cat) {
        for (NodeColor nc : values()) {
            if (nc.category.equals(cat)) return nc;
        }
        return OUTPUT;
    }

    public float red()   { return ((argb >> 16) & 0xFF) / 255.0f; }
    public float green() { return ((argb >>  8) & 0xFF) / 255.0f; }
    public float blue()  { return ((argb)       & 0xFF) / 255.0f; }
}
