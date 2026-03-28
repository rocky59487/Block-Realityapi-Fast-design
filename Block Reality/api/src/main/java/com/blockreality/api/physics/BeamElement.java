package com.blockreality.api.physics;

import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import javax.annotation.concurrent.Immutable;

/**
 * Euler-Bernoulli 梁元素 — 連接兩個相鄰體素的結構力學元素。
 *
 * 靈感來源：NASA/Cornell Voxelyze 開源體素物理引擎。
 *
 * 每個 BeamElement 建立在兩個 face-adjacent 方塊之間，攜帶：
 *   - 截面積 A (m²)：Minecraft 方塊面 = 1m × 1m = 1 m²
 *   - 截面慣性矩 I (m⁴)：正方形截面 I = b⁴/12 = 1/12
 *   - 彈性模量 E (Pa)：由材料的 Rcomp 近似（E ≈ Rcomp × 1000 for concrete）
 *   - 梁長度 L (m)：面中心到面中心 = 1m
 *
 * 簡化假設（適用於 Minecraft 即時物理）：
 *   1. 截面均為 1m × 1m 正方形（不考慮半磚、階梯）
 *   2. 取兩端材料的較弱值（木桶原理）
 *   3. 只考慮軸力 N 和彎矩 M（忽略扭矩，因為方塊不旋轉）
 *   4. 不求解全局剛度矩陣（太慢），改用局部梁判定
 *
 * @param nodeA       端點 A 的方塊位置
 * @param nodeB       端點 B 的方塊位置
 * @param material    梁的等效材料（取兩端較弱者）
 * @param elasticMod  彈性模量 E (Pa)
 * @param momentOfI   截面慣性矩 I (m⁴)
 * @param area        截面積 A (m²)
 * @param length      梁長度 L (m)
 */
@Immutable
public record BeamElement(
    BlockPos nodeA,
    BlockPos nodeB,
    RMaterial material,
    double elasticMod,
    double momentOfI,
    double area,
    double length
) {

    /** 正方形截面 1m×1m 的慣性矩 I = b⁴/12 */
    public static final double UNIT_MOMENT_OF_INERTIA = 1.0 / 12.0;

    /** 截面積 1m² — ★ R3-6 fix: 引用 PhysicsConstants 統一常數來源 */
    public static final double UNIT_AREA = PhysicsConstants.BLOCK_AREA;

    /** 相鄰方塊中心距離 1m */
    public static final double UNIT_LENGTH = 1.0;

    /**
     * Euler 挫屈有效長度係數 K — 依端部約束條件。
     * Minecraft 方塊格間的連接介於鉸接(K=1.0)和固定端(K=0.5)之間，
     * 取工程慣例 K=0.7（一端固定一端鉸接的近似）。
     *
     * 參考：Euler buckling effective length factors (AISC Table C-A-7.1)
     *   K=0.5  兩端固定
     *   K=0.7  一端固定一端鉸接
     *   K=1.0  兩端鉸接
     *   K=2.0  一端固定一端自由（懸臂）
     */
    public static final double EFFECTIVE_LENGTH_FACTOR_K = 0.7;

    /**
     * 從兩個相鄰方塊建立 BeamElement。
     *
     * ★ 改用 RMaterial.getYoungsModulusPa() 取代 Rcomp × 1e9 近似。
     * DefaultMaterial 已提供真實楊氏模量（Eurocode / AISC）：
     *   - 混凝土 C30: E = 30 GPa（真實值）
     *   - 鋼材 Q345:  E = 200 GPa（真實值）
     *   - 木材:       E = 11 GPa（真實值）
     */
    public static BeamElement create(BlockPos a, BlockPos b, RMaterial matA, RMaterial matB) {
        // ★ H-3 fix: null 防護 — 防止自訂方塊或 SnapshotBuilder 傳入 null 材料
        Objects.requireNonNull(matA, "matA must not be null for beam " + a);
        Objects.requireNonNull(matB, "matB must not be null for beam " + b);

        // Voxelyze 風格的複合材料：使用調和平均（適用於異材料連接）
        double E = compositeStiffness(matA, matB);

        // 材料取較弱者（木桶原理）
        RMaterial weaker = matA.getCombinedStrength() <= matB.getCombinedStrength() ? matA : matB;

        return new BeamElement(
            a, b, weaker,
            E,
            UNIT_MOMENT_OF_INERTIA,
            UNIT_AREA,
            UNIT_LENGTH
        );
    }

    /**
     * 雕刻感知版本 — 使用兩端截面的較弱值（瓶頸原理）。
     *
     * 垂直梁使用 min(Ix, Iy)；水平梁可由呼叫端選擇主軸。
     */
    public static BeamElement create(BlockPos a, BlockPos b,
                                     RMaterial matA, RMaterial matB,
                                     ChiselState csA, ChiselState csB) {
        Objects.requireNonNull(matA, "matA must not be null for beam " + a);
        Objects.requireNonNull(matB, "matB must not be null for beam " + b);

        double E = compositeStiffness(matA, matB);
        RMaterial weaker = matA.getCombinedStrength() <= matB.getCombinedStrength() ? matA : matB;

        // 取兩端截面的較弱值（瓶頸原理）
        double area = Math.min(csA.crossSectionArea(), csB.crossSectionArea());
        double I = Math.min(
            Math.min(csA.momentOfInertiaX(), csA.momentOfInertiaY()),
            Math.min(csB.momentOfInertiaX(), csB.momentOfInertiaY())
        );

        return new BeamElement(a, b, weaker, E, I, area, UNIT_LENGTH);
    }

    /**
     * 複合材料的等效剛度 — 調和平均（Voxelyze 方法）。
     *
     * ★ 改用 getYoungsModulusPa() — 使用真實楊氏模量而非 Rcomp 近似。
     *
     * E_composite = 2 × E₁ × E₂ / (E₁ + E₂)
     */
    public static double compositeStiffness(RMaterial matA, RMaterial matB) {
        double EA = matA.getYoungsModulusPa();
        double EB = matB.getYoungsModulusPa();
        if (EA <= 0 || EB <= 0) return 0;
        return 2.0 * EA * EB / (EA + EB);
    }

    // ═══════════════════════════════════════════════════════
    //  梁力學計算
    // ═══════════════════════════════════════════════════════

    /**
     * 軸向剛度 EA/L (N/m)。
     * 衡量梁抵抗軸向壓縮/拉伸的能力。
     */
    public double axialStiffness() {
        return elasticMod * area / length;
    }

    /**
     * 彎曲剛度 EI/L³ (N/m)。
     * 衡量梁抵抗彎曲的能力。
     */
    public double bendingStiffness() {
        return elasticMod * momentOfI / (length * length * length);
    }

    /**
     * Euler 臨界挫屈力 (N)。
     * 梁在軸向壓力下發生側向挫屈的臨界力。
     *
     * ★ 加入有效長度係數 K（AISC C-A-7.1）：
     *   P_cr = π² × E × I / (K × L)²
     *
     * K = 0.7（一端固定一端鉸接，Minecraft 方塊間連接的近似）
     *
     * 對於 1m 長、1m²截面的混凝土梁（E=30GPa）：
     *   P_cr = 9.87 × 30e9 × 0.083 / (0.7)² = ~50 GN（遠超自重）
     *
     * 但對於長跨距結構（多段串聯），等效 L 變大，P_cr 急劇下降。
     */
    public double eulerBucklingLoad() {
        double effectiveLength = EFFECTIVE_LENGTH_FACTOR_K * length;
        return Math.PI * Math.PI * elasticMod * momentOfI / (effectiveLength * effectiveLength);
    }

    /**
     * 最大容許軸力 (N)。
     * 取壓碎強度和挫屈力的最小值。
     *
     * N_max = min(Rcomp × A, P_cr)
     */
    public double maxAxialForce() {
        double rcomp = material.getRcomp();
        // ★ v7-hardening: 防止負值材料屬性產生負容量
        if (rcomp <= 0) return 0;
        double crushLoad = rcomp * 1e6 * area; // MPa → Pa × m² = N
        return Math.min(crushLoad, eulerBucklingLoad());
    }

    /**
     * 最大容許彎矩 (N·m)。
     * 由抗拉強度決定（拉力面先開裂）。
     *
     * M_max = Rtens × I / y_max
     *
     * 對於正方形截面，y_max = 0.5m：
     * M_max = Rtens × (1/12) / 0.5 = Rtens / 6 (MPa × m³ = MN·m)
     */
    public double maxBendingMoment() {
        double rtens = material.getRtens();
        // ★ v7-hardening: 防止負值材料屬性產生負容量
        if (rtens <= 0) return 0;
        double yMax = 0.5; // 截面邊緣到中性軸距離
        return rtens * 1e6 * momentOfI / yMax;
    }

    /**
     * 最大容許剪力 (N)。
     *
     * V_max = Rshear × A
     */
    public double maxShearForce() {
        double rshear = material.getRshear();
        // ★ v7-hardening: 防止負值材料屬性產生負容量
        if (rshear <= 0) return 0;
        return rshear * 1e6 * area;
    }

    /**
     * 安全性比率 — 給定外力下的結構利用率。
     *
     * @param axialForce  軸力 (N)，正=壓，負=拉
     * @param moment      彎矩 (N·m)
     * @param shear       剪力 (N)
     * @return 利用率 (0.0=無應力, 1.0=剛好達到容許, >1.0=破壞)
     */
    public double utilizationRatio(double axialForce, double moment, double shear) {
        double maxAxial = maxAxialForce();
        double maxMoment = maxBendingMoment();
        double maxShear = maxShearForce();

        // ★ C-1 fix: 容量為零時，有力即視為超載 (ratio=2.0)，無力則 ratio=0
        // 防止自訂材料 Rcomp/Rtens/Rshear=0 時除零產生 Infinity/NaN
        double axialRatio = maxAxial > 0 ? Math.abs(axialForce) / maxAxial
                                         : (axialForce != 0 ? 2.0 : 0.0);
        double momentRatio = maxMoment > 0 ? Math.abs(moment) / maxMoment
                                           : (moment != 0 ? 2.0 : 0.0);
        double shearRatio = maxShear > 0 ? Math.abs(shear) / maxShear
                                         : (shear != 0 ? 2.0 : 0.0);

        // ★ v4-fix: von Mises 交互公式（取代錯誤的線性疊加）
        // σ_combined = √(σ_axial² + σ_bending²) 對同軸正應力
        // 剪力獨立檢查（Tresca 準則）
        double normalCombined = Math.sqrt(axialRatio * axialRatio + momentRatio * momentRatio);
        return Math.max(normalCombined, shearRatio);
    }

    /**
     * 應變能 — 梁在軸向力下儲存的彈性能量。
     *
     * U = 0.5 × N² / (E × A) × L
     *
     * 其中：
     *   N = 軸力 (N)
     *   E = 彈性模量 (Pa)
     *   A = 截面積 (m²)
     *   L = 長度 (m)
     *
     * 應變能可用於：
     *   - 結構變形量計算（更大能量 = 更大變形）
     *   - 碰撞分析（釋放的能量）
     *   - 優化設計（最小化應變能）
     *
     * @param axialForce 軸力 (N)
     * @return 應變能 (J)
     */
    public double strainEnergy(double axialForce) {
        if (elasticMod <= 0 || area <= 0 || length <= 0) return 0;
        return 0.5 * axialForce * axialForce / (elasticMod * area) * length;
    }

    /**
     * 安全系數 — 梁能承受外力的倍數。
     *
     * SafetyFactor = 1.0 / utilizationRatio
     *
     * 解釋：
     *   - SF > 1.0: 有剩餘承載能力（SF = 2.0 表示能承受 2 倍當前載重）
     *   - SF = 1.0: 剛好達到限制
     *   - SF < 1.0: 超載（>1.0 需要立即卸載）
     *
     * @param axialForce 軸力 (N)
     * @param moment     彎矩 (N·m)
     * @param shear      剪力 (N)
     * @return 安全系數 (>1.0 = 安全)
     */
    public double safetyFactor(double axialForce, double moment, double shear) {
        double util = utilizationRatio(axialForce, moment, shear);
        if (util <= 0) return Double.POSITIVE_INFINITY;
        return 1.0 / util;
    }

    @Override
    public String toString() {
        return String.format(
            "Beam[%s→%s, mat=%s, E=%.1eGPa, maxN=%.0fkN, maxM=%.0fkN·m]",
            nodeA.toShortString(), nodeB.toShortString(),
            material.getMaterialId(),
            elasticMod / 1e9,
            maxAxialForce() / 1e3,
            maxBendingMoment() / 1e3
        );
    }
}
