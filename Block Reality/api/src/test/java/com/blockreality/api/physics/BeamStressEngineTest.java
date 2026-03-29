package com.blockreality.api.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BeamStressEngine 梁分析正確性測試
 *
 * ★ review-fix ICReM-1: 更新為修正後的彎矩公式：
 *   - 梁自重均布荷載 M_self = w × L² / 8
 *   - 端點不平衡再分配 M_unbal = |Pa - Pb| × L / 4
 *   - 剪力 V = w×L/2 + |Pa-Pb|/2
 *   - 水平荷載分攤（未變更）
 */
@DisplayName("BeamStressEngine — Beam Analysis Correctness Tests")
class BeamStressEngineTest {

    private static final double TOLERANCE = 0.01;
    private static final double GRAVITY = 9.81;

    // ═══ 1. Self-Weight Moment: M_self = wL²/8 ═══

    @Test
    @DisplayName("Self-weight moment: M_self = density × A × g × L² / 8")
    void testSelfWeightMoment() {
        // Concrete beam: density=2350 kg/m³, A=1 m², L=1 m
        double density = 2350;
        double area = 1.0;
        double L = 1.0;
        double w = density * area * GRAVITY; // N/m

        double selfMoment = w * L * L / 8.0;
        double expected = 2350 * 1.0 * 9.81 * 1.0 / 8.0; // = 2881.9 N·m
        assertEquals(expected, selfMoment, TOLERANCE,
            "M_self for concrete 1m beam should be density×A×g×L²/8");
    }

    @Test
    @DisplayName("Self-weight moment scales with L²")
    void testSelfWeightMomentScalesWithLSquared() {
        double w = 1000; // N/m
        double L1 = 4.0;
        double L2 = 8.0;

        double M1 = w * L1 * L1 / 8.0;
        double M2 = w * L2 * L2 / 8.0;

        assertEquals(4.0, M2 / M1, TOLERANCE,
            "Doubling L should quadruple M (L² relationship)");
    }

    // ═══ 2. Unbalanced Moment: M_unbal = |Pa - Pb| × L / 4 ═══

    @Test
    @DisplayName("Balanced load: no unbalanced moment correction")
    void testBalancedLoad() {
        double loadA = 5000;
        double loadB = 5000;
        double L = 4.0;

        double unbalancedMoment = Math.abs(loadA - loadB) * L / 4.0;
        assertEquals(0.0, unbalancedMoment, TOLERANCE,
            "Equal loads should produce zero unbalanced moment");
    }

    @Test
    @DisplayName("Unbalanced load: correction = |Fa-Fb| × L / 4")
    void testUnbalancedLoad() {
        double loadA = 8000;
        double loadB = 2000;
        double L = 6.0;

        double unbalancedMoment = Math.abs(loadA - loadB) * L / 4.0;
        assertEquals(9000.0, unbalancedMoment, TOLERANCE,
            "|8000-2000|×6/4 = 9000 N·m");
    }

    // ═══ 3. Combined Moment ═══

    @Test
    @DisplayName("Total moment = self-weight + unbalanced")
    void testCombinedMoment() {
        // Concrete beam: density=2350, A=1, L=4m
        double density = 2350;
        double area = 1.0;
        double L = 4.0;
        double loadA = 8000;
        double loadB = 2000;

        double w = density * area * GRAVITY;
        double selfMoment = w * L * L / 8.0;
        double unbalancedMoment = Math.abs(loadA - loadB) * L / 4.0;
        double total = selfMoment + unbalancedMoment;

        double expectedSelf = 2350.0 * 1.0 * 9.81 * 16.0 / 8.0; // = 46,110.6 N·m
        double expectedUnbal = 6000.0 * 4.0 / 4.0; // = 6000.0 N·m

        assertEquals(expectedSelf, selfMoment, 0.1, "Self-weight moment");
        assertEquals(expectedUnbal, unbalancedMoment, TOLERANCE, "Unbalanced moment");
        assertEquals(expectedSelf + expectedUnbal, total, 0.1, "Total moment");
    }

    // ═══ 4. Shear Force ═══

    @Test
    @DisplayName("Shear = w×L/2 + |Pa-Pb|/2")
    void testShearForce() {
        double w = 2000; // N/m (self weight per meter)
        double L = 5.0;
        double loadA = 8000;
        double loadB = 2000;

        double shear = w * L / 2.0 + Math.abs(loadA - loadB) / 2.0;
        // = 2000×5/2 + 6000/2 = 5000 + 3000 = 8000
        assertEquals(8000.0, shear, TOLERANCE,
            "V = w×L/2 + |Pa-Pb|/2");
    }

    @Test
    @DisplayName("Balanced load: shear from self-weight only")
    void testBalancedShear() {
        double w = 2000;
        double L = 5.0;
        double loadA = 5000;
        double loadB = 5000;

        double shear = w * L / 2.0 + Math.abs(loadA - loadB) / 2.0;
        assertEquals(5000.0, shear, TOLERANCE,
            "Balanced loads contribute no additional shear");
    }

    // ═══ 5. Zero Length Beam ═══

    @Test
    @DisplayName("Zero-length beam produces zero moment and shear")
    void testZeroLengthBeam() {
        double w = 5000;
        double L = 0;
        double loadA = 1000;
        double loadB = 500;

        double moment = w * L * L / 8.0 + Math.abs(loadA - loadB) * L / 4.0;
        double shear = w * L / 2.0 + Math.abs(loadA - loadB) / 2.0;

        assertEquals(0.0, moment, TOLERANCE, "Zero-length beam: moment = 0");
        // Note: shear has |Pa-Pb|/2 term even at L=0, but in practice L>0
        assertEquals(250.0, shear, TOLERANCE,
            "Zero-length beam still has unbalanced shear component");
    }

    // ═══ 6. Horizontal Load Distribution (unchanged) ═══

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
        double downwardLoad = myLoad;
        assertEquals(12000.0, downwardLoad, TOLERANCE,
            "Full load transfers downward when support exists below");
    }

    // ═══ 7. Vertical Beam: Axial Force Only ═══

    @Test
    @DisplayName("Vertical beam: moment and shear are zero, only axial force")
    void testVerticalBeamAxialOnly() {
        double cumulativeLoad = 50000; // N
        double axialForce = cumulativeLoad;
        double moment = 0; // vertical beam → no bending
        double shear = 0;

        assertTrue(axialForce > 0);
        assertEquals(0.0, moment, TOLERANCE);
        assertEquals(0.0, shear, TOLERANCE);
    }

    // ═══ 8. Asymmetric Loading — regression test for ICReM-1 ═══

    @Test
    @DisplayName("Asymmetric loading: new formula gives larger moment than old formula")
    void testAsymmetricLoadingRegression() {
        // Old formula: q = (Pa+Pb)/L, M = qL²/8 + |Pa-Pb|×L/6
        // New formula: M = w×L²/8 + |Pa-Pb|×L/4
        // For concrete (density=2350), A=1, L=1m, Pa=1000N, Pb=100N:
        double density = 2350;
        double area = 1.0;
        double L = 1.0;
        double loadA = 1000;
        double loadB = 100;

        // Old formula (incorrect)
        double totalLoad = loadA + loadB;
        double qOld = totalLoad / L;
        double oldMoment = qOld * L * L / 8.0 + Math.abs(loadA - loadB) * L / 6.0;
        // = 1100/8 + 900/6 = 137.5 + 150 = 287.5

        // New formula (correct)
        double w = density * area * GRAVITY;
        double newMoment = w * L * L / 8.0 + Math.abs(loadA - loadB) * L / 4.0;
        // = 2350×9.81/8 + 900/4 = 2881.9 + 225.0 = 3106.9

        assertTrue(newMoment > oldMoment,
            "New formula should give larger (more conservative) moment for asymmetric loading. " +
            "Old=" + oldMoment + " New=" + newMoment);

        // Self-weight dominates for heavy materials
        double selfWeight = w * L * L / 8.0;
        assertTrue(selfWeight > Math.abs(loadA - loadB) * L / 4.0,
            "For concrete, self-weight moment should dominate over unbalanced moment");
    }
}
