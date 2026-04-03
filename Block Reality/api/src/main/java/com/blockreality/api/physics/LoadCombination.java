package com.blockreality.api.physics;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * LRFD 荷載組合 — 基於 ASCE 7-22 §2.3.1 與 Eurocode EN 1990 §6.4.3.2。
 *
 * <h3>背景</h3>
 * 結構設計必須考慮多種荷載同時作用的最不利組合。
 * LRFD（Load and Resistance Factor Design）方法對每種荷載乘以偏安全的荷載因子，
 * 確保設計值超過實際荷載的可能性極低（目標可靠度指標 β ≈ 3.0）。
 *
 * <h3>荷載類型</h3>
 * <ul>
 *   <li>{@link LoadType#DEAD} — 永久荷載（自重），γ_D = 1.2 或 0.9</li>
 *   <li>{@link LoadType#LIVE} — 活荷載（人員、設備），γ_L = 1.6</li>
 *   <li>{@link LoadType#WIND} — 風荷載，γ_W = 1.0 或 1.6</li>
 *   <li>{@link LoadType#SEISMIC} — 地震荷載，γ_E = 1.0</li>
 *   <li>{@link LoadType#SNOW} — 雪荷載，γ_S = 1.6</li>
 *   <li>{@link LoadType#THERMAL} — 溫度荷載，γ_T = 1.2</li>
 * </ul>
 *
 * <h3>ASCE 7-22 LRFD 組合</h3>
 * <ol>
 *   <li>LC1: 1.4D</li>
 *   <li>LC2: 1.2D + 1.6L + 0.5S</li>
 *   <li>LC3: 1.2D + 1.6S + 0.5L</li>
 *   <li>LC4: 1.2D + 1.0W + 0.5L + 0.5S</li>
 *   <li>LC5: 0.9D + 1.0W（上揚/傾覆檢查）</li>
 *   <li>LC6: 1.2D + 1.0E + 0.5L</li>
 *   <li>LC7: 0.9D + 1.0E（上揚/傾覆檢查）</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * Map<LoadType, Double> loads = Map.of(
 *     LoadType.DEAD, 100.0,   // 100 N dead load
 *     LoadType.WIND, 50.0     // 50 N wind load
 * );
 * double criticalLoad = LoadCombination.criticalCombinedLoad(loads);
 * // Returns max of all applicable LRFD combinations
 * }</pre>
 *
 * <h3>參考文獻</h3>
 * <ul>
 *   <li>ASCE/SEI 7-22 — Minimum Design Loads for Buildings, §2.3.1</li>
 *   <li>EN 1990:2002 — Eurocode: Basis of structural design, §6.4.3.2</li>
 *   <li>AISC 360-22 — Specification for Structural Steel Buildings</li>
 * </ul>
 *
 * @since 1.1.0
 */
public enum LoadCombination {

    /**
     * LC1: 1.4D — 純自重（施工階段、倉儲結構）。
     */
    LC1_DEAD_ONLY("1.4D", Map.of(LoadType.DEAD, 1.4)),

    /**
     * LC2: 1.2D + 1.6L + 0.5S — 主導活荷載組合（最常控制設計）。
     */
    LC2_DEAD_LIVE("1.2D + 1.6L + 0.5S", Map.of(
        LoadType.DEAD, 1.2,
        LoadType.LIVE, 1.6,
        LoadType.SNOW, 0.5
    )),

    /**
     * LC3: 1.2D + 1.6S + 0.5L — 主導雪荷載組合。
     */
    LC3_DEAD_SNOW("1.2D + 1.6S + 0.5L", Map.of(
        LoadType.DEAD, 1.2,
        LoadType.SNOW, 1.6,
        LoadType.LIVE, 0.5
    )),

    /**
     * LC4: 1.2D + 1.0W + 0.5L + 0.5S — 風荷載組合。
     */
    LC4_DEAD_WIND("1.2D + 1.0W + 0.5L + 0.5S", Map.of(
        LoadType.DEAD, 1.2,
        LoadType.WIND, 1.0,
        LoadType.LIVE, 0.5,
        LoadType.SNOW, 0.5
    )),

    /**
     * LC5: 0.9D + 1.0W — 上揚/傾覆檢查（最小重力 + 最大側向力）。
     */
    LC5_UPLIFT_WIND("0.9D + 1.0W", Map.of(
        LoadType.DEAD, 0.9,
        LoadType.WIND, 1.0
    )),

    /**
     * LC6: 1.2D + 1.0E + 0.5L — 地震荷載組合。
     */
    LC6_DEAD_SEISMIC("1.2D + 1.0E + 0.5L", Map.of(
        LoadType.DEAD, 1.2,
        LoadType.SEISMIC, 1.0,
        LoadType.LIVE, 0.5
    )),

    /**
     * LC7: 0.9D + 1.0E — 地震傾覆檢查。
     */
    LC7_UPLIFT_SEISMIC("0.9D + 1.0E", Map.of(
        LoadType.DEAD, 0.9,
        LoadType.SEISMIC, 1.0
    ));

    // ─── 實例欄位 ──────────────────────────────────────────────────────

    private final String formula;
    private final Map<LoadType, Double> factors;

    LoadCombination(String formula, Map<LoadType, Double> factors) {
        this.formula = formula;
        // 複製到 EnumMap 以獲得最佳效能
        this.factors = Collections.unmodifiableMap(new EnumMap<>(factors));
    }

    // ─── 公開 API ──────────────────────────────────────────────────────

    /**
     * 荷載組合公式（顯示用）。
     * @return 如 "1.2D + 1.6L + 0.5S"
     */
    public String getFormula() {
        return formula;
    }

    /**
     * 取得各荷載類型的因子。
     * @return 不可變的荷載因子映射
     */
    public Map<LoadType, Double> getFactors() {
        return factors;
    }

    /**
     * 取得指定荷載類型的因子（未列入者返回 0.0）。
     */
    public double getFactor(LoadType type) {
        return factors.getOrDefault(type, 0.0);
    }

    /**
     * 計算此組合下的設計荷載值（標量）。
     *
     * <pre>U = Σ(γᵢ × Qᵢ)</pre>
     *
     * @param loads 各荷載類型的特徵值（未折減值）
     * @return 組合後的設計荷載值
     */
    public double combine(@Nonnull Map<LoadType, Double> loads) {
        double result = 0.0;
        for (Map.Entry<LoadType, Double> entry : factors.entrySet()) {
            Double load = loads.get(entry.getKey());
            if (load != null) {
                result += entry.getValue() * load;
            }
        }
        return result;
    }

    /**
     * 計算此組合下的設計荷載向量（3D）。
     *
     * @param loads 各荷載類型的 3D 力向量
     * @return 組合後的 3D 設計荷載向量
     */
    public ForceVector3D combine3D(@Nonnull Map<LoadType, ForceVector3D> loads) {
        double fx = 0, fy = 0, fz = 0;
        double mx = 0, my = 0, mz = 0;
        for (Map.Entry<LoadType, Double> entry : factors.entrySet()) {
            ForceVector3D v = loads.get(entry.getKey());
            if (v != null) {
                double f = entry.getValue();
                fx += f * v.fx();
                fy += f * v.fy();
                fz += f * v.fz();
                mx += f * v.mx();
                my += f * v.my();
                mz += f * v.mz();
            }
        }
        return new ForceVector3D(fx, fy, fz, mx, my, mz);
    }

    // ─── 靜態工具方法 ───────────────────────────────────────────────────

    /**
     * 在所有 LRFD 組合中找出最大設計荷載值（標量包絡）。
     *
     * <p>結構設計取所有組合中的最大值作為設計依據：
     * <pre>U_design = max(LC1, LC2, ..., LC7)</pre>
     *
     * @param loads 各荷載類型的特徵值
     * @return 最大組合荷載值
     */
    public static double criticalCombinedLoad(@Nonnull Map<LoadType, Double> loads) {
        double max = Double.NEGATIVE_INFINITY;
        for (LoadCombination lc : values()) {
            double val = lc.combine(loads);
            if (val > max) {
                max = val;
            }
        }
        return max;
    }

    /**
     * 在所有 LRFD 組合中找出最大設計荷載值和對應的組合。
     *
     * @param loads 各荷載類型的特徵值
     * @return 控制組合及其設計荷載值
     */
    @Nonnull
    public static CriticalResult findCriticalCombination(@Nonnull Map<LoadType, Double> loads) {
        LoadCombination controlling = LC1_DEAD_ONLY;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (LoadCombination lc : values()) {
            double val = lc.combine(loads);
            if (val > maxVal) {
                maxVal = val;
                controlling = lc;
            }
        }
        return new CriticalResult(controlling, maxVal);
    }

    /**
     * 在所有 LRFD 組合中找出產生最大合力的 3D 向量包絡。
     *
     * @param loads 各荷載類型的 3D 力向量
     * @return 控制組合及其設計力向量
     */
    @Nonnull
    public static CriticalResult3D findCriticalCombination3D(
            @Nonnull Map<LoadType, ForceVector3D> loads) {
        LoadCombination controlling = LC1_DEAD_ONLY;
        double maxMagnitude = Double.NEGATIVE_INFINITY;
        ForceVector3D criticalVector = ForceVector3D.ZERO;

        for (LoadCombination lc : values()) {
            ForceVector3D combined = lc.combine3D(loads);
            double mag = combined.forceMagnitude();
            if (mag > maxMagnitude) {
                maxMagnitude = mag;
                controlling = lc;
                criticalVector = combined;
            }
        }
        return new CriticalResult3D(controlling, criticalVector, maxMagnitude);
    }

    /**
     * 取得僅包含重力荷載的組合列表（無風/地震）。
     * 適用於靜態結構分析。
     */
    public static List<LoadCombination> gravityOnlyCombinations() {
        return List.of(LC1_DEAD_ONLY, LC2_DEAD_LIVE, LC3_DEAD_SNOW);
    }

    /**
     * 取得包含側向力的組合列表（風 + 地震）。
     * 適用於完整結構分析。
     */
    public static List<LoadCombination> lateralLoadCombinations() {
        return List.of(LC4_DEAD_WIND, LC5_UPLIFT_WIND, LC6_DEAD_SEISMIC, LC7_UPLIFT_SEISMIC);
    }

    // ─── 結果容器 ───────────────────────────────────────────────────────

    /**
     * 臨界組合搜索結果（標量）。
     */
    public record CriticalResult(LoadCombination combination, double designLoad) {
        @Override
        public String toString() {
            return String.format("%s → %.1f N", combination.formula, designLoad);
        }
    }

    /**
     * 臨界組合搜索結果（3D）。
     */
    public record CriticalResult3D(
        LoadCombination combination,
        ForceVector3D designForce,
        double forceMagnitude
    ) {
        @Override
        public String toString() {
            return String.format("%s → |F|=%.1f N", combination.formula, forceMagnitude);
        }
    }

    @Override
    public String toString() {
        return name() + ": " + formula;
    }
}
