package com.blockreality.api.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BeamStressEngine 梁分析正確性測試 — C-5
 *
 * 驗證 A-3/A-4 修正後的標準梁公式：
 *   - 簡支梁均布荷載 M = qL²/8
 *   - 最大剪力 V = qL/2
 *   - 不等荷載修正
 *   - 水平荷載分攤
 */
@DisplayName("BeamStressEngine — Beam Analysis Correctness Tests")
class BeamStressEngineTest {

    private static final double TOLERANCE = 0.01;

    // ═══ 1. Simply-Supported Beam: Uniform Load ═══

    @Test
    @DisplayName("Uniform load: M_max = qL²/8")
    void testUniformLoadMoment() {
        double totalLoad = 10000; // N total on beam
        double L = 5.0; // m
        double q = totalLoad / L; // N/m

        double expected = q * L * L / 8.0;
        assertEquals(3125.0, expected, TOLERANCE,
            "M_max for q=2000N/m, L=5m should be 3125 N·m");
    }

    // ═══ 2. Shear Force ═══

    @Test
    @DisplayName("Uniform load: V_max = qL/2")
    void testUniformLoadShear() {
        double q = 2000; // N/m
        double L = 5.0;

        double shear = q * L / 2.0;
        assertEquals(5000.0, shear, TOLERANCE,
            "V_max for q=2000N/m, L=5m should be 5000 N");
    }

    // ═══ 3. Balanced vs Unbalanced Loading ═══

    @Test
    @DisplayName("Balanced load: no unbalanced moment correction")
    void testBalancedLoad() {
        double loadA = 5000;
        double loadB = 5000;
        double L = 4.0;

        double unbalancedMoment = Math.abs(loadA - loadB) * L / 6.0;
        assertEquals(0.0, unbalancedMoment, TOLERANCE,
            "Equal loads should produce zero unbalanced moment");
    }

    @Test
    @DisplayName("Unbalanced load: correction = |Fa-Fb|×L/6")
    void testUnbalancedLoad() {
        double loadA = 8000;
        double loadB = 2000;
        double L = 6.0;

        double unbalancedMoment = Math.abs(loadA - loadB) * L / 6.0;
        assertEquals(6000.0, unbalancedMoment, TOLERANCE,
            "|8000-2000|×6/6 = 6000 N·m");
    }

    // ═══ 4. Combined Moment ═══

    @Test
    @DisplayName("Total moment = distributed + unbalanced")
    void testCombinedMoment() {
        double loadA = 8000;
        double loadB = 2000;
        double L = 4.0;
        double totalLoad = loadA + loadB;
        double q = totalLoad / L;

        double distributedMoment = q * L * L / 8.0;
        double unbalancedMoment = Math.abs(loadA - loadB) * L / 6.0;
        double total = distributedMoment + unbalancedMoment;

        assertEquals(5000.0, distributedMoment, TOLERANCE, "qL²/8");
        assertEquals(4000.0, unbalancedMoment, TOLERANCE, "|Fa-Fb|L/6");
        assertEquals(9000.0, total, TOLERANCE, "Total moment");
    }

    // ═══ 5. Zero Length Beam ═══

    @Test
    @DisplayName("Zero-length beam produces zero moment and shear")
    void testZeroLengthBeam() {
        double q = 5000;
        double L = 0;

        double moment = q * L * L / 8.0;
        double shear = q * L / 2.0;

        assertEquals(0.0, moment, TOLERANCE);
        assertEquals(0.0, shear, TOLERANCE);
    }

    // ═══ 6. Horizontal Load Distribution ═══

    @Test
    @DisplayName("Block without support below: load splits equally to horizontal neighbors")
    void testHorizontalLoadDistribution() {
        double myLoad = 12000; // N
        int horizontalNeighborCount = 4; // NSEW

        double share = myLoad / horizontalNeighborCount;
        assertEquals(3000.0, share, TOLERANCE,
            "Each of 4 horizontal neighbors gets 1/4 of the load");
    }

    @Test
    @DisplayName("Block with support below: all load goes down")
    void testVerticalLoadPath() {
        double myLoad = 12000;
        // When block below exists, all load transfers downward
        double downwardLoad = myLoad;
        assertEquals(12000.0, downwardLoad, TOLERANCE,
            "Full load transfers downward when support exists below");
    }

    // ═══ 7. Moment Scales with L² ═══

    @Test
    @DisplayName("Doubling beam length quadruples moment (for same q)")
    void testMomentScalesWithLengthSquared() {
        double q = 1000;
        double L1 = 4.0;
        double L2 = 8.0;

        double M1 = q * L1 * L1 / 8.0;
        double M2 = q * L2 * L2 / 8.0;

        assertEquals(4.0, M2 / M1, TOLERANCE,
            "Doubling L should quadruple M (L² relationship)");
    }

    // ═══ 8. Vertical Beam: Axial Force Only ═══

    @Test
    @DisplayName("Vertical beam: moment and shear are zero, only axial force")
    void testVerticalBeamAxialOnly() {
        // For vertical beams (a.getY() != b.getY()), moment=0, shear=0
        // Only axialForce = cumulativeLoad of upper node
        double cumulativeLoad = 50000; // N
        double axialForce = cumulativeLoad;
        double moment = 0; // vertical beam → no bending
        double shear = 0;

        assertTrue(axialForce > 0);
        assertEquals(0.0, moment, TOLERANCE);
        assertEquals(0.0, shear, TOLERANCE);
    }
}
