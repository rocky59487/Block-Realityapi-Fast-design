package com.blockreality.api.physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForceVector3D 單元測試 — 3D 力/力矩向量運算。
 */
class ForceVector3DTest {

    private static final double EPSILON = 1e-6;

    // ═══════════════════════════════════════════════════════════════
    //  工廠方法
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ZERO 向量所有分量為零")
    void testZero() {
        ForceVector3D z = ForceVector3D.ZERO;
        assertEquals(0, z.fx());
        assertEquals(0, z.fy());
        assertEquals(0, z.fz());
        assertEquals(0, z.mx());
        assertEquals(0, z.my());
        assertEquals(0, z.mz());
        assertTrue(z.isZero());
    }

    @Test
    @DisplayName("gravity() 產生 -Y 方向力")
    void testGravity() {
        ForceVector3D g = ForceVector3D.gravity(100.0);
        assertEquals(0, g.fx(), EPSILON);
        assertEquals(-100.0, g.fy(), EPSILON);
        assertEquals(0, g.fz(), EPSILON);
        assertEquals(0, g.mx(), EPSILON);
        assertTrue(g.isVerticalOnly());
    }

    @Test
    @DisplayName("horizontal() 正規化方向")
    void testHorizontal() {
        ForceVector3D h = ForceVector3D.horizontal(3.0, 4.0, 100.0);
        assertEquals(60.0, h.fx(), EPSILON); // 3/5 * 100
        assertEquals(0, h.fy(), EPSILON);
        assertEquals(80.0, h.fz(), EPSILON); // 4/5 * 100
        assertEquals(100.0, h.forceMagnitude(), EPSILON);
    }

    @Test
    @DisplayName("horizontal() 零方向返回 ZERO")
    void testHorizontalZeroDir() {
        ForceVector3D h = ForceVector3D.horizontal(0, 0, 100.0);
        assertTrue(h.isZero());
    }

    @Test
    @DisplayName("ofForce() 無力矩")
    void testOfForce() {
        ForceVector3D f = ForceVector3D.ofForce(1, 2, 3);
        assertEquals(1, f.fx());
        assertEquals(2, f.fy());
        assertEquals(3, f.fz());
        assertEquals(0, f.mx());
        assertEquals(0, f.my());
        assertEquals(0, f.mz());
    }

    @Test
    @DisplayName("ofMoment() 無力")
    void testOfMoment() {
        ForceVector3D m = ForceVector3D.ofMoment(1, 2, 3);
        assertEquals(0, m.fx());
        assertEquals(0, m.fy());
        assertEquals(0, m.fz());
        assertEquals(1, m.mx());
        assertEquals(2, m.my());
        assertEquals(3, m.mz());
    }

    // ═══════════════════════════════════════════════════════════════
    //  向量運算
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("向量加法")
    void testAdd() {
        ForceVector3D a = new ForceVector3D(1, 2, 3, 4, 5, 6);
        ForceVector3D b = new ForceVector3D(10, 20, 30, 40, 50, 60);
        ForceVector3D sum = a.add(b);
        assertEquals(11, sum.fx(), EPSILON);
        assertEquals(22, sum.fy(), EPSILON);
        assertEquals(33, sum.fz(), EPSILON);
        assertEquals(44, sum.mx(), EPSILON);
        assertEquals(55, sum.my(), EPSILON);
        assertEquals(66, sum.mz(), EPSILON);
    }

    @Test
    @DisplayName("向量減法")
    void testSubtract() {
        ForceVector3D a = new ForceVector3D(10, 20, 30, 40, 50, 60);
        ForceVector3D b = new ForceVector3D(1, 2, 3, 4, 5, 6);
        ForceVector3D diff = a.subtract(b);
        assertEquals(9, diff.fx(), EPSILON);
        assertEquals(18, diff.fy(), EPSILON);
    }

    @Test
    @DisplayName("純量乘法")
    void testScale() {
        ForceVector3D v = new ForceVector3D(1, 2, 3, 4, 5, 6);
        ForceVector3D scaled = v.scale(2.0);
        assertEquals(2, scaled.fx(), EPSILON);
        assertEquals(4, scaled.fy(), EPSILON);
        assertEquals(6, scaled.fz(), EPSILON);
        assertEquals(8, scaled.mx(), EPSILON);
    }

    @Test
    @DisplayName("取反")
    void testNegate() {
        ForceVector3D v = new ForceVector3D(1, -2, 3, -4, 5, -6);
        ForceVector3D neg = v.negate();
        assertEquals(-1, neg.fx(), EPSILON);
        assertEquals(2, neg.fy(), EPSILON);
        assertEquals(-3, neg.fz(), EPSILON);
    }

    @Test
    @DisplayName("加零等恆等")
    void testAddZeroIdentity() {
        ForceVector3D v = new ForceVector3D(1, 2, 3, 4, 5, 6);
        ForceVector3D result = v.add(ForceVector3D.ZERO);
        assertEquals(v, result);
    }

    // ═══════════════════════════════════════════════════════════════
    //  大小計算
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("forceMagnitude — L2 範數")
    void testForceMagnitude() {
        ForceVector3D v = ForceVector3D.ofForce(3, 4, 0);
        assertEquals(5.0, v.forceMagnitude(), EPSILON);
    }

    @Test
    @DisplayName("momentMagnitude — 力矩 L2 範數")
    void testMomentMagnitude() {
        ForceVector3D v = ForceVector3D.ofMoment(0, 3, 4);
        assertEquals(5.0, v.momentMagnitude(), EPSILON);
    }

    @Test
    @DisplayName("horizontalForceMagnitude — X-Z 平面")
    void testHorizontalForceMagnitude() {
        ForceVector3D v = new ForceVector3D(3, 100, 4, 0, 0, 0);
        assertEquals(5.0, v.horizontalForceMagnitude(), EPSILON);
    }

    @Test
    @DisplayName("overturningMoment — 繞水平軸")
    void testOverturningMoment() {
        ForceVector3D v = new ForceVector3D(0, 0, 0, 3, 0, 4);
        assertEquals(5.0, v.overturningMoment(), EPSILON);
    }

    @Test
    @DisplayName("torsion — 繞 Y 軸")
    void testTorsion() {
        ForceVector3D v = new ForceVector3D(0, 0, 0, 10, -5, 20);
        assertEquals(5.0, v.torsion(), EPSILON);
    }

    // ═══════════════════════════════════════════════════════════════
    //  力矩計算（向量外積）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("momentAbout — 向量外積 M = r × F")
    void testMomentAbout() {
        // 力 F = (100, 0, 0) 在力臂 r = (0, 1, 0) 處
        // M = r × F = (1×0 - 0×0, 0×100 - 0×0, 0×0 - 1×100) = (0, 0, -100)
        ForceVector3D f = ForceVector3D.ofForce(100, 0, 0);
        ForceVector3D m = f.momentAbout(0, 1, 0);
        assertEquals(0, m.mx(), EPSILON);
        assertEquals(0, m.my(), EPSILON);
        assertEquals(-100.0, m.mz(), EPSILON);
    }

    @Test
    @DisplayName("momentAbout — 重力在側向力臂產生傾覆力矩")
    void testMomentAbout_Gravity() {
        // 重力 F = (0, -1000, 0) 在力臂 r = (2, 0, 0) 處
        // M = r × F = (0×0 - 0×(-1000), 0×0 - 2×0, 2×(-1000) - 0×0) = (0, 0, -2000)
        ForceVector3D gravity = ForceVector3D.gravity(1000);
        ForceVector3D moment = gravity.momentAbout(2, 0, 0);
        assertEquals(0, moment.mx(), EPSILON);
        assertEquals(0, moment.my(), EPSILON);
        assertEquals(-2000.0, moment.mz(), EPSILON);
    }

    // ═══════════════════════════════════════════════════════════════
    //  判定方法
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isVerticalOnly — 純垂直力")
    void testIsVerticalOnly() {
        assertTrue(ForceVector3D.gravity(100).isVerticalOnly());
        assertFalse(ForceVector3D.ofForce(1, 100, 0).isVerticalOnly());
        assertFalse(new ForceVector3D(0, 100, 0, 1, 0, 0).isVerticalOnly());
    }

    @Test
    @DisplayName("isZero — 所有分量為零")
    void testIsZero() {
        assertTrue(ForceVector3D.ZERO.isZero());
        assertFalse(ForceVector3D.gravity(0.001).isZero());
    }

    @Test
    @DisplayName("toString 格式化輸出")
    void testToString() {
        ForceVector3D v = new ForceVector3D(1, 2, 3, 4, 5, 6);
        String s = v.toString();
        assertTrue(s.contains("F("));
        assertTrue(s.contains("M("));
    }
}
