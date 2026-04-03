package com.blockreality.api.physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoadCombination 單元測試 — ASCE 7-22 LRFD 荷載組合。
 */
class LoadCombinationTest {

    private static final double EPSILON = 0.01;

    // ═══════════════════════════════════════════════════════════════
    //  個別組合計算
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LC1: 1.4D — 純自重")
    void testLC1_DeadOnly() {
        Map<LoadType, Double> loads = Map.of(LoadType.DEAD, 100.0);
        double result = LoadCombination.LC1_DEAD_ONLY.combine(loads);
        assertEquals(140.0, result, EPSILON, "1.4 × 100 = 140");
    }

    @Test
    @DisplayName("LC2: 1.2D + 1.6L + 0.5S")
    void testLC2_DeadLive() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 100.0,
            LoadType.LIVE, 50.0,
            LoadType.SNOW, 20.0
        );
        double result = LoadCombination.LC2_DEAD_LIVE.combine(loads);
        // 1.2×100 + 1.6×50 + 0.5×20 = 120 + 80 + 10 = 210
        assertEquals(210.0, result, EPSILON);
    }

    @Test
    @DisplayName("LC4: 1.2D + 1.0W + 0.5L + 0.5S")
    void testLC4_DeadWind() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 100.0,
            LoadType.WIND, 80.0,
            LoadType.LIVE, 30.0
        );
        double result = LoadCombination.LC4_DEAD_WIND.combine(loads);
        // 1.2×100 + 1.0×80 + 0.5×30 + 0.5×0 = 120 + 80 + 15 = 215
        assertEquals(215.0, result, EPSILON);
    }

    @Test
    @DisplayName("LC5: 0.9D + 1.0W — 上揚檢查使用最小重力因子")
    void testLC5_UpliftWind() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 100.0,
            LoadType.WIND, 50.0
        );
        double result = LoadCombination.LC5_UPLIFT_WIND.combine(loads);
        // 0.9×100 + 1.0×50 = 90 + 50 = 140
        assertEquals(140.0, result, EPSILON);
    }

    @Test
    @DisplayName("LC6: 1.2D + 1.0E + 0.5L — 地震組合")
    void testLC6_DeadSeismic() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 100.0,
            LoadType.SEISMIC, 60.0,
            LoadType.LIVE, 40.0
        );
        double result = LoadCombination.LC6_DEAD_SEISMIC.combine(loads);
        // 1.2×100 + 1.0×60 + 0.5×40 = 120 + 60 + 20 = 200
        assertEquals(200.0, result, EPSILON);
    }

    // ═══════════════════════════════════════════════════════════════
    //  缺失荷載類型（因子乘以 0）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("缺失荷載類型應視為 0")
    void testMissingLoadTypes() {
        Map<LoadType, Double> loads = Map.of(LoadType.DEAD, 100.0);
        // LC2 需要 LIVE 和 SNOW，但缺失 → 視為 0
        double result = LoadCombination.LC2_DEAD_LIVE.combine(loads);
        assertEquals(120.0, result, EPSILON, "1.2×100 + 1.6×0 + 0.5×0 = 120");
    }

    @Test
    @DisplayName("空荷載映射 → 所有組合為 0")
    void testEmptyLoads() {
        Map<LoadType, Double> loads = Map.of();
        double result = LoadCombination.criticalCombinedLoad(loads);
        assertEquals(0.0, result, EPSILON);
    }

    // ═══════════════════════════════════════════════════════════════
    //  臨界組合搜索
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("純自重時 LC1 應為控制組合（1.4D > 1.2D）")
    void testCriticalCombination_DeadOnly() {
        Map<LoadType, Double> loads = Map.of(LoadType.DEAD, 100.0);
        LoadCombination.CriticalResult result =
            LoadCombination.findCriticalCombination(loads);
        assertEquals(LoadCombination.LC1_DEAD_ONLY, result.combination());
        assertEquals(140.0, result.designLoad(), EPSILON);
    }

    @Test
    @DisplayName("大活荷載時 LC2 應為控制組合")
    void testCriticalCombination_HighLive() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 50.0,
            LoadType.LIVE, 200.0
        );
        LoadCombination.CriticalResult result =
            LoadCombination.findCriticalCombination(loads);
        // LC2: 1.2×50 + 1.6×200 = 60 + 320 = 380 — 最大
        assertEquals(LoadCombination.LC2_DEAD_LIVE, result.combination());
        assertEquals(380.0, result.designLoad(), EPSILON);
    }

    @Test
    @DisplayName("criticalCombinedLoad 返回所有組合的最大值")
    void testCriticalCombinedLoad() {
        Map<LoadType, Double> loads = Map.of(
            LoadType.DEAD, 100.0,
            LoadType.LIVE, 80.0,
            LoadType.WIND, 50.0
        );
        double critical = LoadCombination.criticalCombinedLoad(loads);
        // LC2: 1.2×100 + 1.6×80 = 120 + 128 = 248
        // LC4: 1.2×100 + 1.0×50 + 0.5×80 = 120 + 50 + 40 = 210
        // LC1: 1.4×100 = 140
        assertTrue(critical >= 248.0 - EPSILON,
            "Critical should be at least LC2 value (248)");
    }

    // ═══════════════════════════════════════════════════════════════
    //  3D 向量組合
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3D 荷載組合 — 重力 + 風力")
    void testCombine3D() {
        Map<LoadType, ForceVector3D> loads = new EnumMap<>(LoadType.class);
        loads.put(LoadType.DEAD, ForceVector3D.gravity(100.0)); // (0, -100, 0)
        loads.put(LoadType.WIND, ForceVector3D.ofForce(50.0, 0, 0)); // 水平風力

        ForceVector3D result = LoadCombination.LC4_DEAD_WIND.combine3D(loads);
        // LC4: 1.2D + 1.0W → (1.0×50, 1.2×(-100), 0) = (50, -120, 0)
        assertEquals(50.0, result.fx(), EPSILON);
        assertEquals(-120.0, result.fy(), EPSILON);
        assertEquals(0.0, result.fz(), EPSILON);
    }

    @Test
    @DisplayName("3D 臨界組合搜索")
    void testFindCriticalCombination3D() {
        Map<LoadType, ForceVector3D> loads = new EnumMap<>(LoadType.class);
        loads.put(LoadType.DEAD, ForceVector3D.gravity(100.0));
        loads.put(LoadType.LIVE, ForceVector3D.gravity(80.0)); // 額外垂直荷載
        loads.put(LoadType.WIND, ForceVector3D.ofForce(50.0, 0, 0));

        LoadCombination.CriticalResult3D result =
            LoadCombination.findCriticalCombination3D(loads);
        assertNotNull(result.combination());
        assertTrue(result.forceMagnitude() > 0);
        assertNotNull(result.designForce());
    }

    // ═══════════════════════════════════════════════════════════════
    //  組合分類
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("重力組合不含側向荷載因子")
    void testGravityOnlyCombinations() {
        for (LoadCombination lc : LoadCombination.gravityOnlyCombinations()) {
            assertEquals(0.0, lc.getFactor(LoadType.WIND), EPSILON,
                lc.name() + " should have no wind factor");
            assertEquals(0.0, lc.getFactor(LoadType.SEISMIC), EPSILON,
                lc.name() + " should have no seismic factor");
        }
    }

    @Test
    @DisplayName("側向組合包含風或地震因子")
    void testLateralLoadCombinations() {
        for (LoadCombination lc : LoadCombination.lateralLoadCombinations()) {
            boolean hasWind = lc.getFactor(LoadType.WIND) > 0;
            boolean hasSeismic = lc.getFactor(LoadType.SEISMIC) > 0;
            assertTrue(hasWind || hasSeismic,
                lc.name() + " should have wind or seismic factor");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LoadType
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LoadType.isLateral 正確分類")
    void testLoadTypeIsLateral() {
        assertTrue(LoadType.WIND.isLateral());
        assertTrue(LoadType.SEISMIC.isLateral());
        assertFalse(LoadType.DEAD.isLateral());
        assertFalse(LoadType.LIVE.isLateral());
        assertFalse(LoadType.SNOW.isLateral());
    }

    @Test
    @DisplayName("LoadType.isGravity 正確分類")
    void testLoadTypeIsGravity() {
        assertTrue(LoadType.DEAD.isGravity());
        assertTrue(LoadType.LIVE.isGravity());
        assertTrue(LoadType.SNOW.isGravity());
        assertFalse(LoadType.WIND.isGravity());
        assertFalse(LoadType.SEISMIC.isGravity());
    }

    @Test
    @DisplayName("所有 7 個 LRFD 組合均已定義")
    void testAllCombinationsDefined() {
        assertEquals(7, LoadCombination.values().length);
    }
}
