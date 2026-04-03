package com.blockreality.api.sph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SPH 核心函數（Cubic Spline Kernel）單元測試。
 *
 * 驗證核心函數的數學性質：
 * - 正規化（3D 球面積分 ≈ 1.0）
 * - 對稱性（W(r) = W(-r)）
 * - 緊支撐（W = 0 for r > 2h）
 * - 單調遞減
 * - 梯度正確性
 */
class SPHKernelTest {

    private static final double EPSILON = 1e-8;

    @Test
    void testCubicSpline_atOrigin() {
        double h = 1.0;
        double w0 = SPHKernel.cubicSpline(0.0, h);
        // W(0, 1) = σ₃ × 1.0 = 1/(π×1³) = 1/π ≈ 0.31831
        assertEquals(1.0 / Math.PI, w0, EPSILON,
            "W(0, h=1) should equal 1/π (3D normalization constant)");
    }

    @Test
    void testCubicSpline_compactSupport() {
        double h = 2.0;
        // At exactly r = 2h, kernel should be 0
        assertEquals(0.0, SPHKernel.cubicSpline(2.0 * h, h), EPSILON,
            "W(2h, h) must be 0 (compact support boundary)");
        // Beyond 2h, kernel must be 0
        assertEquals(0.0, SPHKernel.cubicSpline(2.0 * h + 0.01, h), EPSILON,
            "W(r > 2h, h) must be 0");
        assertEquals(0.0, SPHKernel.cubicSpline(100.0, h), EPSILON,
            "W(large r, h) must be 0");
    }

    @Test
    void testCubicSpline_monotonicallyDecreasing() {
        double h = 1.5;
        double prev = SPHKernel.cubicSpline(0.0, h);
        for (double r = 0.1; r <= 2.0 * h; r += 0.1) {
            double current = SPHKernel.cubicSpline(r, h);
            assertTrue(current <= prev + EPSILON,
                String.format("W must be monotonically decreasing: W(%.1f)=%f > W(%.1f)=%f",
                    r, current, r - 0.1, prev));
            prev = current;
        }
    }

    @Test
    void testCubicSpline_alwaysNonNegative() {
        double h = 1.0;
        for (double r = 0.0; r <= 3.0; r += 0.05) {
            double w = SPHKernel.cubicSpline(r, h);
            assertTrue(w >= 0.0,
                String.format("W(r=%.2f, h=1) = %f must be >= 0", r, w));
        }
    }

    @Test
    void testCubicSpline_continuityAtQ1() {
        // At q = 1 (r = h), both piecewise branches should yield the same value
        double h = 1.0;
        double wMinus = SPHKernel.cubicSpline(h - 1e-10, h);
        double wPlus  = SPHKernel.cubicSpline(h + 1e-10, h);
        assertEquals(wMinus, wPlus, 1e-6,
            "Kernel must be continuous at q = 1 (r = h)");
    }

    @Test
    void testCubicSpline_3DNormalization() {
        // Numerical integration: ∫₀^{2h} W(r,h) × 4πr² dr ≈ 1.0
        double h = 1.0;
        double integral = 0.0;
        int steps = 10000;
        double dr = (2.0 * h) / steps;
        for (int i = 0; i < steps; i++) {
            double r = (i + 0.5) * dr; // midpoint rule
            double w = SPHKernel.cubicSpline(r, h);
            integral += w * 4.0 * Math.PI * r * r * dr;
        }
        assertEquals(1.0, integral, 0.01,
            "3D spherical integral of W(r,h) should be ≈ 1.0 (normalization)");
    }

    @Test
    void testCubicSpline_scalingWithH() {
        // Larger h → broader but lower peak (normalization preserved)
        double w_h1 = SPHKernel.cubicSpline(0.0, 1.0);
        double w_h2 = SPHKernel.cubicSpline(0.0, 2.0);
        assertTrue(w_h1 > w_h2,
            "Larger h should give lower peak (energy spreads over larger volume)");
        // W(0, 2h) should be 8× smaller (σ₃ ∝ 1/h³)
        assertEquals(w_h1 / 8.0, w_h2, EPSILON,
            "W(0, 2h) = W(0, h) / 8 due to 1/h³ normalization");
    }

    @Test
    void testCubicSplineGradient_zeroAtOrigin() {
        double h = 1.0;
        assertEquals(0.0, SPHKernel.cubicSplineGradient(0.0, h), EPSILON,
            "Gradient must be 0 at r = 0 (symmetry)");
    }

    @Test
    void testCubicSplineGradient_zeroAtCompactSupport() {
        double h = 1.0;
        assertEquals(0.0, SPHKernel.cubicSplineGradient(2.0 * h, h), EPSILON,
            "Gradient must be 0 at r = 2h");
        assertEquals(0.0, SPHKernel.cubicSplineGradient(3.0, h), EPSILON,
            "Gradient must be 0 beyond support radius");
    }

    @Test
    void testCubicSplineGradient_negativeInInterior() {
        // Kernel decreases radially → gradient should be ≤ 0
        double h = 1.0;
        for (double r = 0.1; r < 2.0 * h; r += 0.1) {
            double grad = SPHKernel.cubicSplineGradient(r, h);
            assertTrue(grad <= EPSILON,
                String.format("Gradient at r=%.1f should be <= 0 (kernel decreasing), got %f", r, grad));
        }
    }

    @Test
    void testCubicSplineGradient_continuityAtQ1() {
        double h = 1.0;
        double gMinus = SPHKernel.cubicSplineGradient(h - 1e-10, h);
        double gPlus  = SPHKernel.cubicSplineGradient(h + 1e-10, h);
        assertEquals(gMinus, gPlus, 1e-5,
            "Gradient must be continuous at q = 1 (r = h)");
    }

    @Test
    void testSupportRadius() {
        assertEquals(4.0, SPHKernel.supportRadius(2.0), EPSILON,
            "Support radius = 2h");
        assertEquals(2.0, SPHKernel.supportRadius(1.0), EPSILON);
    }

    @Test
    void testCubicSpline_invalidH() {
        assertThrows(IllegalArgumentException.class,
            () -> SPHKernel.cubicSpline(1.0, 0.0),
            "h = 0 should throw");
        assertThrows(IllegalArgumentException.class,
            () -> SPHKernel.cubicSpline(1.0, -1.0),
            "h < 0 should throw");
    }

    @Test
    void testCubicSplineGradient_invalidH() {
        assertThrows(IllegalArgumentException.class,
            () -> SPHKernel.cubicSplineGradient(1.0, 0.0),
            "h = 0 should throw");
    }
}
