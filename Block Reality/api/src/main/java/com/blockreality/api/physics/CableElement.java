package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import javax.annotation.concurrent.Immutable;

/**
 * Cable/Rope Element — Tension-only structural element for Block Reality physics.
 *
 * 繩索/纜索元素 — 僅承受拉力的結構元素。
 *
 * A cable element differs from a beam in that it can only sustain tension (pulling forces).
 * Under compression, the cable goes slack and contributes zero stiffness.
 *
 * Design model:
 *   - Two endpoints (BlockPos nodeA, nodeB) at distance L apart
 *   - Rest length and current length (from endpoint positions)
 *   - Cross-sectional area A (default 0.01 m² for rope)
 *   - Young's modulus E (from RMaterial.getYoungsModulusPa())
 *   - Tension T = E × A × strain (clamped to ≥ 0)
 *   - Tensile capacity: Rtens × 1e6 Pa × A
 *
 * When strain ≤ 0 (compression or neutral), the cable is slack: T = 0, stiffness = 0.
 * When strain > 0 (tension), the cable resists: T = E × A × strain.
 *
 * @param nodeA       Starting endpoint position
 * @param nodeB       Ending endpoint position
 * @param material    Cable material (provides Rcomp, Rtens, density)
 * @param restLength  Original length of the cable (m)
 * @param area        Cross-sectional area (m²), default 0.01 for rope
 */
@Immutable
// TODO review-fix #20: 缺少單元測試。建議覆蓋：create() 工廠、restLength 計算、
//   maxTension() 公式、immutable() 端點防禦、null 參數拒絕。
public record CableElement(
    BlockPos nodeA,
    BlockPos nodeB,
    RMaterial material,
    double restLength,
    double area
) {

    /** Default rope cross-sectional area: 0.01 m² (≈10 cm diameter natural fiber rope) */
    public static final double DEFAULT_CABLE_AREA = 0.01;

    /**
     * Create a cable element between two block positions.
     *
     * 建立兩個方塊位置之間的纜索元素。
     *
     * The rest length is calculated as the Euclidean distance between nodeA and nodeB.
     * The cable uses DEFAULT_CABLE_AREA for cross-section.
     *
     * @param a             Starting block position
     * @param b             Ending block position
     * @param cableMaterial Cable material (RMaterial)
     * @return A new CableElement with rest length = distance(a, b)
     */
    public static CableElement create(BlockPos a, BlockPos b, RMaterial cableMaterial) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        double restLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // ★ review-fix #3: 確保 BlockPos 不可變，防止 MutableBlockPos 破壞 @Immutable 契約
        return new CableElement(a.immutable(), b.immutable(), cableMaterial, restLen, DEFAULT_CABLE_AREA);
    }

    /**
     * Get the current length of the cable (distance between nodeA and nodeB).
     *
     * @return Current length in meters
     */
    public double currentLength() {
        double dx = nodeB.getX() - nodeA.getX();
        double dy = nodeB.getY() - nodeA.getY();
        double dz = nodeB.getZ() - nodeA.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate engineering strain: (L_current - L_rest) / L_rest
     *
     * 計算工程應變：(目前長度 - 靜止長度) / 靜止長度
     *
     * Positive strain = tension (cable is stretched).
     * Negative strain = compression (cable is compressed, will go slack).
     * Zero strain = perfect length (no internal force).
     *
     * @return Strain value (dimensionless)
     */
    public double strain() {
        if (restLength <= 0) return 0;
        return (currentLength() - restLength) / restLength;
    }

    /**
     * Check if this cable is slack (no tension).
     *
     * 檢查纜索是否鬆弛（無張力）。
     *
     * Cables go slack when strain ≤ 0 (compressed or neutral).
     * Slack cables contribute zero stiffness and carry no tension.
     *
     * @return true if strain ≤ 0, false if strain > 0 (under tension)
     */
    public boolean isSlack() {
        return strain() <= 0;
    }

    /**
     * Calculate the current tension force in the cable.
     *
     * 計算纜索中目前的張力。
     *
     * Tension follows Hooke's law: T = E × A × ε
     *   where E = Young's modulus, A = area, ε = strain
     *
     * Young's modulus is obtained from RMaterial.getYoungsModulusPa() which provides
     * a centralized empirical approximation: E ≈ max(Rcomp, Rtens) × 1e9 Pa.
     *
     * Cables cannot sustain compression, so tension is clamped to ≥ 0.
     * If the cable would be pushed (strain ≤ 0), it goes slack and T = 0.
     *
     * @return Tension force in Newtons (≥ 0)
     */
    public double tension() {
        if (isSlack()) return 0;
        double elasticMod = material.getYoungsModulusPa();
        return Math.max(0, elasticMod * area * strain());
    }

    /**
     * Get the maximum allowable tension (tensile capacity) of this cable.
     *
     * 獲得纜索的最大容許張力（抗拉能力）。
     *
     * Calculated as: T_max = Rtens × A (Pa × m² = N)
     *   where Rtens is the material's tensile strength in Pa.
     *
     * @return Maximum tension in Newtons
     */
    public double maxTension() {
        return material.getRtens() * 1e6 * area;  // Rtens (MPa) → Pa × area = N
    }

    /**
     * Calculate the utilization ratio: current tension / maximum tension.
     *
     * 計算利用率：目前張力 / 最大張力。
     *
     * Used to assess how close the cable is to breaking.
     *
     * @return Utilization ratio (0.0 = no stress, 1.0 = at limit, > 1.0 = overstressed)
     */
    public double utilizationRatio() {
        double maxT = maxTension();
        if (maxT <= 0) return 0;
        return tension() / maxT;
    }

    /**
     * Check if this cable is broken (utilization exceeds 100%).
     *
     * 檢查纜索是否已斷裂（利用率超過 100%）。
     *
     * A cable is considered broken when the current tension exceeds the maximum
     * allowable tension for the material and cross-section.
     *
     * @return true if utilizationRatio() > 1.0, false otherwise
     */
    public boolean isBroken() {
        return utilizationRatio() > 1.0;
    }

    /**
     * Get the stiffness of this cable element.
     *
     * 獲得纜索元素的剛度。
     *
     * For tension elements: stiffness = E × A / L
     * For slack cables: stiffness = 0 (cannot push, only pull)
     *
     * @return Stiffness in N/m
     */
    public double stiffness() {
        if (isSlack()) return 0;
        double elasticMod = material.getYoungsModulusPa();
        if (restLength <= 0) return 0;
        return elasticMod * area / restLength;
    }

    @Override
    public String toString() {
        return String.format(
            "Cable[%s→%s, L=%.2fm, T=%.1fN/%.1fN, slack=%s, mat=%s]",
            nodeA.toShortString(),
            nodeB.toShortString(),
            currentLength(),
            tension(),
            maxTension(),
            isSlack(),
            material.getMaterialId()
        );
    }
}
