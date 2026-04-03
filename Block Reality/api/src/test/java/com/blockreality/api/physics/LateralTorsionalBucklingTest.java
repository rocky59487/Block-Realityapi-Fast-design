package com.blockreality.api.physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LateralTorsionalBuckling 單元測試 — AISC §F2 / EN 1993-1-1 §6.3.2。
 */
class LateralTorsionalBucklingTest {

    private static final double EPSILON = 0.01;

    // 鋼材 Q345 的典型值
    private static final double STEEL_E = 200e9;   // 200 GPa
    private static final double STEEL_G = 77e9;    // 77 GPa
    private static final double STEEL_FY = 345e6;  // 345 MPa

    // 混凝土 C30 的典型值
    private static final double CONCRETE_E = 30e9;  // 30 GPa
    private static final double CONCRETE_G = 12.5e9; // ~12.5 GPa
    private static final double CONCRETE_FY = 30e6;  // 30 MPa

    // ═══════════════════════════════════════════════════════════════
    //  彈性 LTB 臨界彎矩
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("短跨距鋼梁 M_cr 極高（正方形截面抗 LTB）")
    void testBlockCriticalMoment_ShortSpan() {
        // 1m 跨距的正方形截面鋼梁，M_cr 應遠高於使用荷載
        double mcr = LateralTorsionalBuckling.blockCriticalMoment(
            STEEL_E, STEEL_G, 1.0);
        assertTrue(mcr > 1e9, "1m span steel block should have M_cr > 1 GN·m, got " + mcr);
    }

    @Test
    @DisplayName("M_cr 隨跨距增加而降低")
    void testBlockCriticalMoment_DecreasesWithSpan() {
        double mcr1 = LateralTorsionalBuckling.blockCriticalMoment(STEEL_E, STEEL_G, 1.0);
        double mcr5 = LateralTorsionalBuckling.blockCriticalMoment(STEEL_E, STEEL_G, 5.0);
        double mcr10 = LateralTorsionalBuckling.blockCriticalMoment(STEEL_E, STEEL_G, 10.0);
        assertTrue(mcr1 > mcr5, "M_cr should decrease: 1m > 5m");
        assertTrue(mcr5 > mcr10, "M_cr should decrease: 5m > 10m");
    }

    @Test
    @DisplayName("零跨距返回 MAX_VALUE")
    void testBlockCriticalMoment_ZeroSpan() {
        double mcr = LateralTorsionalBuckling.blockCriticalMoment(STEEL_E, STEEL_G, 0.0);
        assertEquals(Double.MAX_VALUE, mcr);
    }

    @Test
    @DisplayName("零彈性模量返回 MAX_VALUE")
    void testBlockCriticalMoment_ZeroE() {
        double mcr = LateralTorsionalBuckling.blockCriticalMoment(0, STEEL_G, 5.0);
        assertEquals(Double.MAX_VALUE, mcr);
    }

    @Test
    @DisplayName("混凝土 M_cr 低於鋼材（同跨距）")
    void testBlockCriticalMoment_ConcreteVsSteel() {
        double mcrSteel = LateralTorsionalBuckling.blockCriticalMoment(
            STEEL_E, STEEL_G, 5.0);
        double mcrConcrete = LateralTorsionalBuckling.blockCriticalMoment(
            CONCRETE_E, CONCRETE_G, 5.0);
        assertTrue(mcrSteel > mcrConcrete,
            "Steel M_cr should exceed concrete M_cr for same span");
    }

    // ═══════════════════════════════════════════════════════════════
    //  利用率
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("小彎矩 + 短跨距 → 低利用率")
    void testBlockUtilizationRatio_Safe() {
        double ur = LateralTorsionalBuckling.blockUtilizationRatio(
            1000.0, STEEL_E, STEEL_G, 2.0); // 1 kN·m on 2m span
        assertTrue(ur < 0.01, "Should be very low utilization, got " + ur);
    }

    @Test
    @DisplayName("利用率隨彎矩線性增加")
    void testBlockUtilizationRatio_LinearWithMoment() {
        double ur1 = LateralTorsionalBuckling.blockUtilizationRatio(
            1000.0, STEEL_E, STEEL_G, 5.0);
        double ur2 = LateralTorsionalBuckling.blockUtilizationRatio(
            2000.0, STEEL_E, STEEL_G, 5.0);
        assertEquals(ur1 * 2, ur2, ur1 * 0.01, "UR should scale linearly with moment");
    }

    // ═══════════════════════════════════════════════════════════════
    //  三區域分類（AISC §F2）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("極短跨距 → PLASTIC 區域")
    void testClassifyRegion_Plastic() {
        LateralTorsionalBuckling.LTBRegion region =
            LateralTorsionalBuckling.classifyRegion(0.5, STEEL_E, STEEL_FY);
        assertEquals(LateralTorsionalBuckling.LTBRegion.PLASTIC, region);
    }

    @Test
    @DisplayName("極長跨距 → ELASTIC 區域")
    void testClassifyRegion_Elastic() {
        LateralTorsionalBuckling.LTBRegion region =
            LateralTorsionalBuckling.classifyRegion(100.0, STEEL_E, STEEL_FY);
        assertEquals(LateralTorsionalBuckling.LTBRegion.ELASTIC, region);
    }

    @Test
    @DisplayName("L_p 為正值且合理")
    void testPlasticLimitLength() {
        double lp = LateralTorsionalBuckling.blockPlasticLimitLength(STEEL_E, STEEL_FY);
        assertTrue(lp > 0, "L_p should be positive");
        // 鋼材的 L_p 應該在合理範圍（幾公尺到幾十公尺）
        assertTrue(lp > 1.0 && lp < 100.0,
            "Steel L_p should be reasonable, got " + lp + "m");
    }

    @Test
    @DisplayName("零降伏強度 → L_p = 0")
    void testPlasticLimitLength_ZeroFy() {
        double lp = LateralTorsionalBuckling.blockPlasticLimitLength(STEEL_E, 0);
        assertEquals(0, lp);
    }

    // ═══════════════════════════════════════════════════════════════
    //  設計彎矩容量
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("短跨距設計容量 = M_p（塑性區）")
    void testDesignMomentCapacity_Plastic() {
        double mp = STEEL_FY * 0.25; // Z = b³/4 = 0.25
        double mn = LateralTorsionalBuckling.designMomentCapacity(
            STEEL_E, STEEL_G, STEEL_FY, 0.1); // 0.1m span
        assertEquals(mp, mn, mp * 0.01, "Short span should yield M_p");
    }

    @Test
    @DisplayName("設計容量隨跨距增加而降低")
    void testDesignMomentCapacity_DecreasesWithSpan() {
        double mn1 = LateralTorsionalBuckling.designMomentCapacity(
            STEEL_E, STEEL_G, STEEL_FY, 1.0);
        double mn50 = LateralTorsionalBuckling.designMomentCapacity(
            STEEL_E, STEEL_G, STEEL_FY, 50.0);
        assertTrue(mn1 >= mn50,
            "Design capacity should not increase with span");
    }

    @Test
    @DisplayName("設計容量不超過 M_p")
    void testDesignMomentCapacity_CappedAtMp() {
        double mp = STEEL_FY * 0.25;
        for (double lb = 0.1; lb <= 100; lb += 5) {
            double mn = LateralTorsionalBuckling.designMomentCapacity(
                STEEL_E, STEEL_G, STEEL_FY, lb);
            assertTrue(mn <= mp + 1,
                "M_n should not exceed M_p at L_b=" + lb);
        }
    }

    @Test
    @DisplayName("零材料參數 → 容量 = 0")
    void testDesignMomentCapacity_ZeroParams() {
        assertEquals(0, LateralTorsionalBuckling.designMomentCapacity(
            0, STEEL_G, STEEL_FY, 5.0));
        assertEquals(0, LateralTorsionalBuckling.designMomentCapacity(
            STEEL_E, STEEL_G, 0, 5.0));
    }

    // ═══════════════════════════════════════════════════════════════
    //  一般公式（非方塊截面）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("elasticCriticalMoment — 自訂截面參數")
    void testElasticCriticalMoment_Custom() {
        // I-beam 截面參數（W310×39）
        double Iy = 7.23e-6;   // m⁴
        double J = 0.116e-6;   // m⁴
        double Cw = 88.6e-9;   // m⁶
        double Lb = 5.0;       // m

        double mcr = LateralTorsionalBuckling.elasticCriticalMoment(
            STEEL_E, STEEL_G, Iy, J, Cw, Lb, 1.0);

        assertTrue(mcr > 0, "M_cr should be positive");
        assertTrue(Double.isFinite(mcr), "M_cr should be finite");
    }
}
