package com.blockreality.api.physics;

import javax.annotation.Nonnull;

/**
 * 側向扭轉挫屈計算器 — Eurocode EN 1993-1-1 §6.3.2 / AISC 360-22 §F2。
 *
 * <h3>背景</h3>
 * 當梁受彎時，壓力翼緣可能發生側向位移並伴隨截面扭轉，
 * 稱為側向扭轉挫屈（Lateral-Torsional Buckling, LTB）。
 * 這是梁設計中最常見的失穩模式之一，尤其對長跨距、窄截面的梁。
 *
 * <h3>三個區域</h3>
 * <ol>
 *   <li><b>塑性區</b>（L_b ≤ L_p）：截面可達到完全塑性彎矩 M_p，
 *       不發生 LTB。</li>
 *   <li><b>非彈性區</b>（L_p &lt; L_b ≤ L_r）：臨界彎矩在 M_p 和 0.7F_y·S_x
 *       之間線性插值。</li>
 *   <li><b>彈性區</b>（L_b &gt; L_r）：使用彈性 LTB 臨界彎矩公式。</li>
 * </ol>
 *
 * <h3>Minecraft 應用</h3>
 * 在 Minecraft 方塊結構中：
 * <ul>
 *   <li>正方形截面 1m×1m 的 LTB 抵抗力極高（I_y = I_z = 1/12）</li>
 *   <li>雕刻方塊（10×10×10 體素）可能產生薄壁截面，此時 LTB 成為控制因素</li>
 *   <li>多格水平懸臂結構的等效跨距增大，降低 LTB 抵抗力</li>
 * </ul>
 *
 * <h3>參考文獻</h3>
 * <ul>
 *   <li>EN 1993-1-1:2005 §6.3.2 — Lateral-torsional buckling of members</li>
 *   <li>AISC 360-22 §F2 — Doubly Symmetric Compact I-Shaped Members</li>
 *   <li>Timoshenko, S.P. & Gere, J.M. (1961). Theory of Elastic Stability, Ch. 6</li>
 * </ul>
 *
 * @since 1.1.0
 */
public final class LateralTorsionalBuckling {

    private LateralTorsionalBuckling() {} // 不可實例化

    // ─── 常數 ──────────────────────────────────────────────────────────

    /**
     * 修正係數 C_b — 彎矩梯度修正。
     * 均布荷載簡支梁 C_b ≈ 1.14，保守取 1.0。
     * AISC §F1(1): C_b = 12.5M_max / (2.5M_max + 3M_A + 4M_B + 3M_C)
     */
    private static final double DEFAULT_CB = 1.0;

    /**
     * 正方形截面的扭轉常數 J（Saint-Venant）。
     * 對 b×b 正方形截面：J ≈ 0.1406 × b⁴
     * 對 1m×1m：J ≈ 0.1406 m⁴
     *
     * 參考：Timoshenko (1961), Table 10.1
     */
    private static final double UNIT_TORSION_CONSTANT = 0.1406;

    /**
     * 正方形截面的翹曲常數 C_w。
     * 對正方形實心截面，翹曲效應可忽略（C_w ≈ 0）。
     * 這是因為正方形截面的翹曲自由度被約束。
     */
    private static final double UNIT_WARPING_CONSTANT = 0.0;

    // ─── 公開 API ──────────────────────────────────────────────────────

    /**
     * 彈性側向扭轉挫屈臨界彎矩 M_cr。
     *
     * <p>Timoshenko 經典公式（AISC §F2-4 的一般形式）：
     * <pre>
     * M_cr = C_b × (π/L_b) × √(E·I_y·G·J + (π·E/L_b)² · I_y·C_w)
     * </pre>
     *
     * <p>對正方形實心截面（C_w ≈ 0），簡化為：
     * <pre>
     * M_cr = C_b × (π/L_b) × √(E·I_y·G·J)
     * </pre>
     *
     * @param elasticModPa    楊氏模量 E (Pa)
     * @param shearModPa      剪力模量 G (Pa)
     * @param momentOfInertiaY 弱軸慣性矩 I_y (m⁴)
     * @param torsionConstant  Saint-Venant 扭轉常數 J (m⁴)
     * @param warpingConstant  翹曲常數 C_w (m⁶)
     * @param unbracedLength   無側撐長度 L_b (m)
     * @param cb               彎矩梯度修正係數 C_b
     * @return 彈性 LTB 臨界彎矩 M_cr (N·m)
     */
    public static double elasticCriticalMoment(
            double elasticModPa, double shearModPa,
            double momentOfInertiaY, double torsionConstant,
            double warpingConstant, double unbracedLength, double cb) {

        if (unbracedLength <= 0 || elasticModPa <= 0) return Double.MAX_VALUE;

        double piOverL = Math.PI / unbracedLength;
        double term1 = elasticModPa * momentOfInertiaY * shearModPa * torsionConstant;
        double term2 = piOverL * piOverL * elasticModPa * elasticModPa
                      * momentOfInertiaY * warpingConstant;

        if (term1 + term2 < 0) return Double.MAX_VALUE; // 數值保護
        return cb * piOverL * Math.sqrt(term1 + term2);
    }

    /**
     * 標準 Minecraft 方塊的 LTB 臨界彎矩。
     *
     * <p>使用 1m×1m 正方形截面的預設參數。
     *
     * @param elasticModPa  楊氏模量 E (Pa)
     * @param shearModPa    剪力模量 G (Pa)
     * @param unbracedLength 無側撐長度 L_b (m)，即水平懸臂跨距
     * @return M_cr (N·m)
     */
    public static double blockCriticalMoment(
            double elasticModPa, double shearModPa, double unbracedLength) {
        return elasticCriticalMoment(
            elasticModPa, shearModPa,
            PhysicsConstants.FULL_MOMENT_OF_INERTIA,
            UNIT_TORSION_CONSTANT,
            UNIT_WARPING_CONSTANT,
            unbracedLength, DEFAULT_CB);
    }

    /**
     * LTB 利用率 — 實際彎矩與 LTB 臨界彎矩之比。
     *
     * @param actualMoment   實際彎矩 M (N·m)
     * @param elasticModPa   楊氏模量 E (Pa)
     * @param shearModPa     剪力模量 G (Pa)
     * @param unbracedLength 無側撐長度 L_b (m)
     * @return 利用率 UR = M / M_cr（> 1.0 表示 LTB 失效）
     */
    public static double blockUtilizationRatio(
            double actualMoment, double elasticModPa, double shearModPa,
            double unbracedLength) {
        double mcr = blockCriticalMoment(elasticModPa, shearModPa, unbracedLength);
        if (mcr <= 0) return Double.MAX_VALUE;
        return Math.abs(actualMoment) / mcr;
    }

    // ─── AISC 三區域判定 ────────────────────────────────────────────────

    /**
     * AISC §F2 塑性極限無側撐長度 L_p。
     *
     * <pre>L_p = 1.76 × r_y × √(E / F_y)</pre>
     *
     * 當 L_b ≤ L_p 時，截面可達到完全塑性彎矩 M_p。
     *
     * @param radiusOfGyrationY 弱軸迴轉半徑 r_y (m)
     * @param elasticModPa      楊氏模量 E (Pa)
     * @param yieldStrengthPa   降伏強度 F_y (Pa)
     * @return L_p (m)
     */
    public static double plasticLimitLength(
            double radiusOfGyrationY, double elasticModPa, double yieldStrengthPa) {
        if (yieldStrengthPa <= 0 || elasticModPa <= 0) return 0;
        return 1.76 * radiusOfGyrationY * Math.sqrt(elasticModPa / yieldStrengthPa);
    }

    /**
     * 標準方塊截面的 L_p。
     */
    public static double blockPlasticLimitLength(double elasticModPa, double yieldStrengthPa) {
        return plasticLimitLength(
            ColumnBucklingCalculator.blockRadiusOfGyration(),
            elasticModPa, yieldStrengthPa);
    }

    /**
     * 判定 LTB 區域：PLASTIC / INELASTIC / ELASTIC。
     *
     * @param unbracedLength   L_b (m)
     * @param elasticModPa     E (Pa)
     * @param yieldStrengthPa  F_y (Pa)
     * @return LTB 行為區域
     */
    @Nonnull
    public static LTBRegion classifyRegion(
            double unbracedLength, double elasticModPa, double yieldStrengthPa) {
        double lp = blockPlasticLimitLength(elasticModPa, yieldStrengthPa);
        // L_r ≈ π × r_y × √(E / (0.7 × F_y))（AISC §F2-6 簡化）
        double ry = ColumnBucklingCalculator.blockRadiusOfGyration();
        double lr = Math.PI * ry * Math.sqrt(elasticModPa / (0.7 * yieldStrengthPa));

        if (unbracedLength <= lp) return LTBRegion.PLASTIC;
        if (unbracedLength <= lr) return LTBRegion.INELASTIC;
        return LTBRegion.ELASTIC;
    }

    /**
     * LTB 行為區域分類。
     */
    public enum LTBRegion {
        /** 塑性區 — 可達完全塑性彎矩 M_p，不發生 LTB。 */
        PLASTIC,
        /** 非彈性區 — 臨界彎矩在 M_p 和 M_r 之間線性插值。 */
        INELASTIC,
        /** 彈性區 — 使用彈性 LTB 臨界彎矩公式。 */
        ELASTIC
    }

    /**
     * AISC 三區域的設計彎矩容量 M_n。
     *
     * <ul>
     *   <li>塑性區：M_n = M_p = F_y × Z（Z = 塑性截面模數）</li>
     *   <li>非彈性區：M_n = M_p − (M_p − 0.7F_y·S_x) × (L_b − L_p) / (L_r − L_p)</li>
     *   <li>彈性區：M_n = M_cr（彈性 LTB 臨界彎矩）</li>
     * </ul>
     *
     * @param elasticModPa      E (Pa)
     * @param shearModPa        G (Pa)
     * @param yieldStrengthPa   F_y (Pa)
     * @param unbracedLength    L_b (m)
     * @return 設計彎矩容量 M_n (N·m)
     */
    public static double designMomentCapacity(
            double elasticModPa, double shearModPa,
            double yieldStrengthPa, double unbracedLength) {

        if (elasticModPa <= 0 || yieldStrengthPa <= 0) return 0;

        // 截面模數（正方形 1m×1m）
        // 塑性截面模數 Z = b³/4 = 0.25 m³（正方形）
        // 彈性截面模數 S = b³/6 ≈ 0.1667 m³
        double Zx = 0.25;  // plastic section modulus
        double Sx = PhysicsConstants.FULL_SECTION_MODULUS;

        double Mp = yieldStrengthPa * Zx;
        double Mr = 0.7 * yieldStrengthPa * Sx;

        LTBRegion region = classifyRegion(unbracedLength, elasticModPa, yieldStrengthPa);

        switch (region) {
            case PLASTIC:
                return Mp;

            case INELASTIC: {
                double lp = blockPlasticLimitLength(elasticModPa, yieldStrengthPa);
                double ry = ColumnBucklingCalculator.blockRadiusOfGyration();
                double lr = Math.PI * ry * Math.sqrt(elasticModPa / (0.7 * yieldStrengthPa));
                double ratio = (unbracedLength - lp) / (lr - lp);
                return Math.max(Mr, Mp - (Mp - Mr) * ratio);
            }

            case ELASTIC:
                return Math.min(Mp,
                    blockCriticalMoment(elasticModPa, shearModPa, unbracedLength));

            default:
                return Mp;
        }
    }
}
