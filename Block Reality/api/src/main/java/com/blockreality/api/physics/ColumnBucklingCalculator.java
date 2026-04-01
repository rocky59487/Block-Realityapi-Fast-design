package com.blockreality.api.physics;

import javax.annotation.Nonnull;

/**
 * 柱挫屈臨界應力計算器 — M9 Johnson 拋物線公式修正
 *
 * <h3>背景</h3>
 * 歐拉公式（Euler）僅適用於長柱（高細長比 λ > λ_c）。
 * 對於短柱或中等細長比的柱（λ ≤ λ_c），歐拉公式會高估臨界應力，
 * 有時甚至超過材料降伏強度，造成物理上不合理的結果。
 *
 * <h3>Johnson 拋物線（CRC 公式）</h3>
 * 由 Column Research Council（CRC）提出，現為 AISC 鋼構設計規範基礎：
 * <ul>
 *   <li>細長比 λ = L_eff / r（其中 r = √(I/A) 為迴轉半徑）</li>
 *   <li>臨界細長比 λ_c = π × √(2E / F_y)（Euler 與 Johnson 的轉換點）</li>
 *   <li>長柱（λ > λ_c）：σ_cr = π²E / λ²（Euler）</li>
 *   <li>短/中柱（λ ≤ λ_c）：σ_cr = F_y × [1 − F_y × λ² / (4π²E)]（Johnson 拋物線）</li>
 * </ul>
 *
 * <h3>物理意義</h3>
 * <ul>
 *   <li>λ → 0（極短柱）：σ_cr → F_y（由材料強度控制）</li>
 *   <li>λ = λ_c（轉換點）：兩公式在此相切，確保 C¹ 連續性</li>
 *   <li>λ → ∞（極長柱）：σ_cr → 0（由幾何不穩定控制）</li>
 * </ul>
 *
 * <h3>單位規範</h3>
 * 所有輸入與輸出均使用 SI 制：
 * <ul>
 *   <li>E（楊氏模量）：Pa（如 200e9 Pa = 200 GPa）</li>
 *   <li>F_y（降伏強度）：Pa（如 345e6 Pa = 345 MPa）</li>
 *   <li>σ_cr（臨界應力）：Pa</li>
 *   <li>λ（細長比）：無量綱</li>
 * </ul>
 *
 * <h3>參考文獻</h3>
 * <ul>
 *   <li>AISC 360-22 §E3 — Flexural Buckling of Members Without Slender Elements</li>
 *   <li>Salmon, Johnson & Malhas (2009) — Steel Structures: Design and Behavior, Ch. 14</li>
 *   <li>EN 1993-1-1 §6.3.1 — Buckling curves（歐規等效版本）</li>
 * </ul>
 *
 * @since 1.1.0 (M9 fix)
 */
public final class ColumnBucklingCalculator {

    private ColumnBucklingCalculator() {} // 不可實例化，純工具類

    // ─── 公差常數 ──────────────────────────────────────────────────────

    /**
     * 細長比最小值護欄：λ < MIN_LAMBDA 時視為剛體（σ_cr = F_y）。
     * 防止 λ = 0 時除以零。
     */
    private static final double MIN_LAMBDA = 0.001;

    /**
     * 材料強度最小護欄（Pa）：避免 F_y = 0 的奇點。
     */
    private static final double MIN_YIELD_STRENGTH_PA = 1.0;

    /**
     * 彈性模量最小護欄（Pa）：避免 E = 0 的奇點。
     */
    private static final double MIN_ELASTIC_MODULUS_PA = 1.0;

    // ─── 公開 API ──────────────────────────────────────────────────────

    /**
     * 計算臨界細長比 λ_c（Euler 與 Johnson 的轉換點）。
     *
     * <pre>λ_c = π × √(2E / F_y)</pre>
     *
     * 當 λ > λ_c 時使用 Euler 公式；λ ≤ λ_c 時使用 Johnson 拋物線。
     *
     * @param elasticModulusPa 楊氏模量 E (Pa)，必須 > 0
     * @param yieldStrengthPa  降伏強度 F_y (Pa)，必須 > 0
     * @return 臨界細長比 λ_c（無量綱，> 0）
     * @throws IllegalArgumentException 若 E 或 F_y ≤ 0
     */
    public static double criticalSlendernessRatio(double elasticModulusPa, double yieldStrengthPa) {
        validatePositive(elasticModulusPa, "elasticModulusPa");
        validatePositive(yieldStrengthPa, "yieldStrengthPa");
        return Math.PI * Math.sqrt(2.0 * elasticModulusPa / yieldStrengthPa);
    }

    /**
     * 歐拉挫屈臨界應力（適用長柱，λ > λ_c）。
     *
     * <pre>σ_cr = π²E / λ²</pre>
     *
     * @param elasticModulusPa 楊氏模量 E (Pa)
     * @param slendernessRatio 細長比 λ = L_eff / r（無量綱，> 0）
     * @return 歐拉臨界應力 σ_cr (Pa)
     * @throws IllegalArgumentException 若 E ≤ 0 或 λ ≤ 0
     */
    public static double eulerBucklingStress(double elasticModulusPa, double slendernessRatio) {
        validatePositive(elasticModulusPa, "elasticModulusPa");
        if (slendernessRatio < MIN_LAMBDA) {
            throw new IllegalArgumentException(
                "細長比 λ 必須 > 0，得到: " + slendernessRatio);
        }
        return (Math.PI * Math.PI * elasticModulusPa) / (slendernessRatio * slendernessRatio);
    }

    /**
     * Johnson 拋物線臨界應力（適用短/中柱，λ ≤ λ_c）。
     *
     * <pre>σ_cr = F_y × [1 − F_y × λ² / (4π²E)]</pre>
     *
     * @param elasticModulusPa 楊氏模量 E (Pa)
     * @param yieldStrengthPa  降伏強度 F_y (Pa)
     * @param slendernessRatio 細長比 λ（無量綱）
     * @return Johnson 臨界應力 σ_cr (Pa)
     * @throws IllegalArgumentException 若任何輸入非正
     */
    public static double johnsonBucklingStress(double elasticModulusPa, double yieldStrengthPa,
                                               double slendernessRatio) {
        validatePositive(elasticModulusPa, "elasticModulusPa");
        validatePositive(yieldStrengthPa, "yieldStrengthPa");
        if (slendernessRatio < 0) {
            throw new IllegalArgumentException(
                "細長比 λ 不可為負，得到: " + slendernessRatio);
        }
        // 極短柱（λ → 0）：σ_cr = F_y
        if (slendernessRatio < MIN_LAMBDA) {
            return yieldStrengthPa;
        }
        double lambda2 = slendernessRatio * slendernessRatio;
        return yieldStrengthPa * (1.0 - (yieldStrengthPa * lambda2) / (4.0 * Math.PI * Math.PI * elasticModulusPa));
    }

    /**
     * 統一入口 — 依據細長比自動選擇 Euler 或 Johnson 公式。
     *
     * <p>設計準則：
     * <ul>
     *   <li>λ = 0（剛體）→ F_y（材料強度上限）</li>
     *   <li>0 &lt; λ ≤ λ_c → Johnson 拋物線</li>
     *   <li>λ &gt; λ_c → Euler 公式</li>
     *   <li>σ_cr 始終被夾制在 [0, F_y]（不超過降伏強度，不為負值）</li>
     * </ul>
     *
     * @param elasticModulusPa 楊氏模量 E (Pa)，必須 > 0
     * @param yieldStrengthPa  降伏強度 F_y (Pa)，必須 > 0
     * @param slendernessRatio 細長比 λ = L_eff / r（無量綱，≥ 0）
     * @return 臨界挫屈應力 σ_cr (Pa)，∈ [0, F_y]
     * @throws IllegalArgumentException 若 E ≤ 0、F_y ≤ 0 或 λ &lt; 0
     */
    public static double criticalBucklingStress(double elasticModulusPa, double yieldStrengthPa,
                                                double slendernessRatio) {
        validatePositive(elasticModulusPa, "elasticModulusPa");
        validatePositive(yieldStrengthPa, "yieldStrengthPa");
        if (slendernessRatio < 0) {
            throw new IllegalArgumentException(
                "細長比 λ 不可為負，得到: " + slendernessRatio);
        }

        // 極短柱（λ < MIN_LAMBDA）→ 材料強度控制
        if (slendernessRatio < MIN_LAMBDA) {
            return yieldStrengthPa;
        }

        double lambdaC = criticalSlendernessRatio(elasticModulusPa, yieldStrengthPa);
        double sigma;
        if (slendernessRatio <= lambdaC) {
            // 短/中柱：Johnson 拋物線
            sigma = johnsonBucklingStress(elasticModulusPa, yieldStrengthPa, slendernessRatio);
        } else {
            // 長柱：Euler 公式
            sigma = eulerBucklingStress(elasticModulusPa, slendernessRatio);
        }

        // 安全夾制：σ_cr ∈ [0, F_y]
        return Math.max(0.0, Math.min(sigma, yieldStrengthPa));
    }

    /**
     * 計算等效細長比。
     *
     * <pre>λ = K × L / r</pre>
     *
     * 其中：
     * <ul>
     *   <li>K：有效長度係數（兩端鉸接 K=1.0，兩端固定 K=0.5，懸臂 K=2.0）</li>
     *   <li>L：柱體實際長度 (m)</li>
     *   <li>r：迴轉半徑 r = √(I/A) (m)</li>
     * </ul>
     *
     * @param effectiveLengthFactor K（有效長度係數）
     * @param columnLengthM         L（柱長度，公尺）
     * @param radiusOfGyrationM     r（迴轉半徑，公尺）
     * @return 細長比 λ（無量綱）
     * @throws IllegalArgumentException 若 r ≤ 0 或 L &lt; 0
     */
    public static double slendernessRatio(double effectiveLengthFactor, double columnLengthM,
                                          double radiusOfGyrationM) {
        if (radiusOfGyrationM <= 0) {
            throw new IllegalArgumentException(
                "迴轉半徑 r 必須 > 0，得到: " + radiusOfGyrationM);
        }
        if (columnLengthM < 0) {
            throw new IllegalArgumentException(
                "柱長度 L 不可為負，得到: " + columnLengthM);
        }
        return (effectiveLengthFactor * columnLengthM) / radiusOfGyrationM;
    }

    /**
     * Minecraft 方塊柱的迴轉半徑（1m × 1m 正方形截面）。
     *
     * <pre>r = √(I/A) = √((b⁴/12) / b²) = b/√12</pre>
     *
     * 對於 1m × 1m 截面：r = 1/√12 ≈ 0.2887 m
     *
     * @return 標準方塊截面的迴轉半徑 (m)
     */
    public static double blockRadiusOfGyration() {
        // A = 1.0 m², I = 1.0/12 m⁴ → r = sqrt(I/A) = sqrt(1/12)
        return Math.sqrt(PhysicsConstants.FULL_MOMENT_OF_INERTIA / PhysicsConstants.BLOCK_AREA);
    }

    /**
     * 利用率檢查：實際壓力是否超過臨界挫屈應力。
     *
     * @param actualStressPa   實際軸向壓應力 σ (Pa)
     * @param elasticModulusPa 楊氏模量 E (Pa)
     * @param yieldStrengthPa  降伏強度 F_y (Pa)
     * @param slendernessRatio 細長比 λ
     * @return 利用率 UR = σ / σ_cr（> 1.0 表示挫屈失效）
     */
    public static double bucklingUtilizationRatio(double actualStressPa, double elasticModulusPa,
                                                   double yieldStrengthPa, double slendernessRatio) {
        double sigmaCr = criticalBucklingStress(elasticModulusPa, yieldStrengthPa, slendernessRatio);
        if (sigmaCr <= 0) return Double.MAX_VALUE; // 安全護欄
        return actualStressPa / sigmaCr;
    }

    // ─── 私有方法 ───────────────────────────────────────────────────────

    private static void validatePositive(double value, @Nonnull String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必須 > 0，得到: " + value);
        }
    }
}
