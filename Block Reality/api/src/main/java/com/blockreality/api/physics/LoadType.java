package com.blockreality.api.physics;

/**
 * 荷載類型分類 — 依 ASCE 7-22 §2.1 分類。
 *
 * <p>每種荷載類型具有不同的時間特性與統計分布，
 * 因此在 LRFD 組合中使用不同的荷載因子。
 *
 * @see LoadCombination
 * @since 1.1.0
 */
public enum LoadType {

    /**
     * 永久荷載（Dead Load, D）。
     * 結構自重、固定設備。在 Minecraft 中為方塊自重。
     * 變異性低 → γ_D = 1.2（加載）或 0.9（上揚檢查）。
     */
    DEAD("D", "永久荷載"),

    /**
     * 活荷載（Live Load, L）。
     * 人員、可移動設備、暫時存放物。
     * 在 Minecraft 中為實體（生物、掉落物）對方塊的荷載。
     * 變異性高 → γ_L = 1.6。
     */
    LIVE("L", "活荷載"),

    /**
     * 風荷載（Wind Load, W）。
     * 風壓作用於結構表面。方向性荷載（水平為主）。
     * 公式：q = 0.5 × ρ_air × v² × C_d。
     * γ_W = 1.0（與其他主導荷載組合時）。
     */
    WIND("W", "風荷載"),

    /**
     * 地震荷載（Earthquake Load, E）。
     * 地震慣性力。主要為水平方向，含垂直分量。
     * 公式：V_base = C_s × W（等效靜力法）。
     * γ_E = 1.0。
     */
    SEISMIC("E", "地震荷載"),

    /**
     * 雪荷載（Snow Load, S）。
     * 積雪在屋頂的重量。主要為垂直方向。
     * γ_S = 0.5~1.6（取決於是否為主導荷載）。
     */
    SNOW("S", "雪荷載"),

    /**
     * 溫度荷載（Thermal Load, T）。
     * 溫差引起的膨脹/收縮應力。無方向性。
     * γ_T = 1.2。
     */
    THERMAL("T", "溫度荷載");

    private final String symbol;
    private final String description;

    LoadType(String symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    /** 荷載符號（用於公式顯示）。 */
    public String getSymbol() {
        return symbol;
    }

    /** 荷載說明。 */
    public String getDescription() {
        return description;
    }

    /** 是否為側向荷載（風或地震）。 */
    public boolean isLateral() {
        return this == WIND || this == SEISMIC;
    }

    /** 是否為重力荷載（垂直方向）。 */
    public boolean isGravity() {
        return this == DEAD || this == LIVE || this == SNOW;
    }
}
