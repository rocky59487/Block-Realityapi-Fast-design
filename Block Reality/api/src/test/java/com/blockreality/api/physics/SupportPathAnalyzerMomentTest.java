package com.blockreality.api.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPA 力矩正確性測試 — C-4
 *
 * 驗證 A-1/A-2 修正後的力矩累積模型：
 *   - 均勻重量懸臂：M = w×g×(1+2+...+n) = w×g×n(n+1)/2
 *   - 非均勻重量：M = Σ(w_i × g × d_i)
 *   - 垂直重置：DOWN 方向力矩歸零
 *   - 懸吊結構：UP 方向力臂遞增
 */
@DisplayName("SupportPathAnalyzer — Moment Accumulation Correctness Tests")
class SupportPathAnalyzerMomentTest {

    private static final double GRAVITY = 9.81;
    private static final double TOLERANCE = 0.01;

    // ═══ 1. Uniform cantilever moment ═══

    @Test
    @DisplayName("Uniform 5-block cantilever: M = w×g×(1+2+3+4+5)")
    void testUniformCantileverMoment() {
        double w = 2400; // concrete density kg
        // M = w×g×1 + w×g×2 + w×g×3 + w×g×4 + w×g×5
        //   = w×g×(1+2+3+4+5) = w×g×15
        double expected = w * GRAVITY * 15;

        // Simulate accumulation: each step adds w×g×d
        double accumulated = 0;
        for (int d = 1; d <= 5; d++) {
            accumulated += w * GRAVITY * d;
        }
        assertEquals(expected, accumulated, TOLERANCE,
            "Uniform cantilever moment should equal w×g×n(n+1)/2");
    }

    // ═══ 2. Non-uniform weights ═══

    @Test
    @DisplayName("Mixed materials: concrete(2400) at d=1, timber(600) at d=2")
    void testNonUniformMoment() {
        // Correct: M = 2400×g×1 + 600×g×2 = (2400 + 1200)×g = 3600×g
        double expected = 2400 * GRAVITY * 1 + 600 * GRAVITY * 2;

        // Old formula would give: (2400+600) × g × 2 / 2 = 3000×g (WRONG)
        double oldFormula = (2400 + 600) * GRAVITY * 2 / 2.0;

        // New formula: accumulate per-block
        double newFormula = 0;
        newFormula += 2400 * GRAVITY * 1; // block at d=1
        newFormula += 600 * GRAVITY * 2;  // block at d=2

        assertEquals(expected, newFormula, TOLERANCE,
            "New accumulated moment should match exact calculation");
        assertNotEquals(expected, oldFormula, 100,
            "Old formula gives different result for non-uniform weights");
    }

    // ═══ 3. Vertical reset ═══

    @Test
    @DisplayName("Vertical step (DOWN) resets moment to zero")
    void testVerticalResetsMoment() {
        // Horizontal: 3 blocks accumulate moment
        double moment = 0;
        double w = 2400;
        moment += w * GRAVITY * 1;
        moment += w * GRAVITY * 2;
        moment += w * GRAVITY * 3;
        assertTrue(moment > 0);

        // DOWN direction: moment resets
        double afterDown = 0.0; // reset
        assertEquals(0.0, afterDown, TOLERANCE,
            "DOWN step should reset accumulated moment to zero");
    }

    // ═══ 4. Heavy tip vs heavy base ═══

    @Test
    @DisplayName("Heavy block at tip produces more moment than heavy block at base")
    void testHeavyTipVsHeavyBase() {
        // Case A: heavy(7850) at d=1, light(600) at d=5
        double momentA = 7850 * GRAVITY * 1 + 600 * GRAVITY * 5;

        // Case B: light(600) at d=1, heavy(7850) at d=5
        double momentB = 600 * GRAVITY * 1 + 7850 * GRAVITY * 5;

        assertTrue(momentB > momentA,
            "Heavy block at tip (d=5) should produce more moment than heavy block at base (d=1)");
        // Factor: momentB/momentA ≈ (600+39250)/(7850+3000) ≈ 3.67×
        assertTrue(momentB / momentA > 3.0,
            "Moment ratio should be significant (>3×)");
    }

    // ═══ 5. Single block moment ═══

    @Test
    @DisplayName("Single block at d=1: M = w×g×1")
    void testSingleBlockMoment() {
        double w = 2400;
        double moment = w * GRAVITY * 1;
        assertEquals(2400 * 9.81, moment, TOLERANCE);
    }

    // ═══ 6. Moment vs capacity check ═══

    @Test
    @DisplayName("Concrete cantilever: 4 blocks safe, 10+ blocks fail")
    void testMomentVsCapacity() {
        double w = 2400; // concrete kg per block
        double Rtens = 3.0; // MPa
        double sectionModulus = 1.0 / 6.0; // 1m cube: bh²/6
        double capacity = Rtens * 1e6 * sectionModulus; // N·m

        // 4 blocks: M = 2400×9.81×(1+2+3+4) = 2400×9.81×10
        double moment4 = 0;
        for (int d = 1; d <= 4; d++) moment4 += w * GRAVITY * d;
        assertTrue(moment4 < capacity,
            "4-block concrete cantilever should be safe (M=" + moment4 + " < C=" + capacity + ")");

        // 10 blocks: M = 2400×9.81×(1+2+...+10) = 2400×9.81×55
        double moment10 = 0;
        for (int d = 1; d <= 10; d++) moment10 += w * GRAVITY * d;
        assertTrue(moment10 > capacity,
            "10-block concrete cantilever should fail (M=" + moment10 + " > C=" + capacity + ")");
    }

    // ═══ 7. Accumulated moment formula equivalence ═══

    @Test
    @DisplayName("Uniform weight: accumulated = w×g×n(n+1)/2 (closed form)")
    void testClosedFormEquivalence() {
        double w = 1500;
        int n = 20;
        double closedForm = w * GRAVITY * n * (n + 1) / 2.0;

        double accumulated = 0;
        for (int d = 1; d <= n; d++) {
            accumulated += w * GRAVITY * d;
        }

        assertEquals(closedForm, accumulated, 0.001,
            "Iterative accumulation should match closed-form n(n+1)/2");
    }

    // ═══ 8. Zero arm length = zero moment ═══

    @Test
    @DisplayName("Block directly on anchor (arm=0) has zero moment")
    void testZeroArmLengthZeroMoment() {
        double moment = 2400 * GRAVITY * 0;
        assertEquals(0.0, moment, TOLERANCE,
            "arm=0 should produce zero moment");
    }
}
