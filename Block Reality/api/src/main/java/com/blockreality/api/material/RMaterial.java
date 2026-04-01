package com.blockreality.api.material;

/**
 * R氏材料介面 — Block Reality 物理系統的材料抽象。
 *
 * 所有材料參數使用工程單位：
 *   - 強度：MPa (百萬帕斯卡)
 *   - 密度：kg/m³
 *
 * 設計哲學：
 *   Minecraft 每格 = 1m³，材料參數直接對應現實工程值，
 *   讓結構計算可與現實數據交叉驗證。
 *
 * @since 1.0.0
 */
public interface RMaterial {

    /** 抗壓強度 (MPa) */
    double getRcomp();

    /** 抗拉強度 (MPa) */
    double getRtens();

    /** 抗剪強度 (MPa) */
    double getRshear();

    /** 密度 (kg/m³) */
    double getDensity();

    /** 材料識別 ID (如 "plain_concrete", "rebar") */
    String getMaterialId();

    /**
     * 是否為不可破壞材料（如 BEDROCK）。
     *
     * 當此方法回傳 {@code true} 時，物理求解器跳過所有強度利用率計算，
     * 直接視為 utilization = 0，避免極大強度值（1e15 MPa）在浮點運算中
     * 產生 NaN 或數值不穩定問題。
     *
     * ★ P3-fix (2025-04): 以語意標記取代 1e15 浮點溢位風險。
     *
     * @return {@code true} 若此材料不受任何荷載破壞
     */
    default boolean isIndestructible() {
        return false;
    }

    /**
     * 綜合強度指標 — 三軸強度的幾何平均。
     * 用於快速比較材料的整體結構能力。
     */
    default double getCombinedStrength() {
        return Math.cbrt(getRcomp() * getRtens() * getRshear());
    }

    /**
     * 是否為延性材料。
     * 延性材料在破壞前會產生顯著變形（鋼筋、鐵塊），
     * 脆性材料則無預警突然斷裂（混凝土、玻璃）。
     *
     * 判定：抗壓/抗拉 < 10 → 延性
     */
    default boolean isDuctile() {
        if (getRtens() == 0) return false;
        return (getRcomp() / getRtens()) < 10.0;
    }

    /**
     * 楊氏模量 E (Pa) — 材料抵抗彈性變形的能力。
     *
     * 預設實作使用經驗公式近似（向後相容）：
     *   E ≈ max(Rcomp, Rtens) × 1000 (MPa → GPa)
     *
     * DefaultMaterial 已覆寫為真實工程值（來源：Engineering Toolbox / Eurocode）。
     * 自訂材料建議覆寫此方法提供精確值。
     *
     * @return Young's modulus in Pa
     */
    default double getYoungsModulusPa() {
        return Math.max(getRcomp(), getRtens()) * 1e9;
    }

    /**
     * 降伏強度 (MPa) — 材料開始產生塑性變形的臨界應力。
     *
     * 對脆性材料（混凝土、玻璃）近似等於 Rcomp（無明顯降伏平台）。
     * 對延性材料（鋼材）應提供真實降伏強度。
     *
     * 預設：取 Rcomp 和 Rtens 中較小的非零值。
     *
     * @return yield strength in MPa
     */
    default double getYieldStrength() {
        double comp = getRcomp();
        double tens = getRtens();
        if (tens <= 0) return comp;
        if (comp <= 0) return tens;
        return Math.min(comp, tens);
    }

    /**
     * 泊松比 ν — 橫向應變與軸向應變之比。
     *
     * 參考值（Engineering Toolbox / Eurocode）：
     *   Steel:    0.27–0.30
     *   Concrete: 0.15–0.20
     *   Wood:     0.20–0.40（各向異性，取主方向近似）
     *   Glass:    0.18–0.24
     *   Brick:    0.10–0.20
     *   Sand:     0.25–0.35
     *
     * 預設：0.20（混凝土/石材的典型值）
     *
     * @return Poisson's ratio (dimensionless, typically 0.0–0.5)
     */
    default double getPoissonsRatio() {
        return 0.20;
    }

    /**
     * 剪力模量 G (Pa) — 由 E 和 ν 推導。
     *
     * G = E / (2 × (1 + ν))
     *
     * 此為各向同性材料的精確關係式。
     *
     * @return shear modulus in Pa
     */
    default double getShearModulusPa() {
        double E = getYoungsModulusPa();
        double nu = getPoissonsRatio();
        return E / (2.0 * (1.0 + nu));
    }

    /**
     * 最大無支撐跨距（格數）。
     *
     * 靈感來源：Block Physics Overhaul 的梁強度模型。
     * 定義水平方向上方塊能延伸的最大距離，超過則自動判定 CANTILEVER_BREAK。
     * 提供比力矩公式更直覺的設計指引。
     *
     * 預設基於抗拉強度的經驗公式：
     *   span = clamp(sqrt(Rtens) × 2, 1, 64)
     *
     * 各材料大致結果：
     *   SAND(0)     → 1   （不能懸空）
     *   GLASS(0.5)  → 1   （極脆）
     *   BRICK(0.5)  → 1   （磚不能懸臂）
     *   CONCRETE(3) → 3   （混凝土短跨）
     *   TIMBER(8)   → 5   （木材中跨）
     *   STEEL(500)  → 44  （鋼材長跨）
     *   RC_FUSION   → ~25 （鋼筋混凝土）
     *   BEDROCK     → 64  （無限）
     */
    default int getMaxSpan() {
        double rtens = getRtens();
        if (rtens <= 0) return 1;
        int span = (int) (Math.sqrt(rtens) * 2.0);
        return Math.max(1, Math.min(64, span));
    }
}
