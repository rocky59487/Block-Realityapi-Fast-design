package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComputeRangePolicy 測試 — 驗證 VRAM 壓力分級策略。
 */
class ComputeRangePolicyTest {

    private VramBudgetManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new VramBudgetManager();
        // 100 MB 總預算
        mgr.initManual(100L * 1024 * 1024, 0.66f, 0.22f, 0.12f);
    }

    @Test
    @DisplayName("壓力 < 0.6 → L0_FULL + 相場 + 多網格")
    void testLowPressure_FullPrecision() {
        // 用量 10%（10 MB）
        mgr.tryRecord(1L, 10L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        int voxels = 1000; // 小 island
        ComputeRangePolicy.ComputeConfig config = ComputeRangePolicy.decide(mgr, voxels);

        assertNotNull(config);
        assertEquals(ComputeRangePolicy.GridLevel.L0_FULL, config.gridLevel());
        assertEquals(1.0f, config.stepsMultiplier(), 0.001f);
        assertTrue(config.allocatePhaseField());
        assertTrue(config.allocateMultigrid());
    }

    @Test
    @DisplayName("壓力 0.6-0.85 → L0_FULL 但跳過相場")
    void testMediumPressure_NoPhaseField() {
        // 用量 70%
        mgr.tryRecord(1L, 70L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        int voxels = 100; // 很小 island（needed = 6200 bytes, free ~30MB）
        ComputeRangePolicy.ComputeConfig config = ComputeRangePolicy.decide(mgr, voxels);

        assertNotNull(config);
        assertEquals(ComputeRangePolicy.GridLevel.L0_FULL, config.gridLevel());
        assertFalse(config.allocatePhaseField());
        assertTrue(config.allocateMultigrid());
    }

    @Test
    @DisplayName("壓力 0.85-0.95 → L1_COARSE + 半步")
    void testHighPressure_CoarseGrid() {
        // 用量 90%
        mgr.tryRecord(1L, 90L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        int voxels = 1000;
        ComputeRangePolicy.ComputeConfig config = ComputeRangePolicy.decide(mgr, voxels);

        assertNotNull(config);
        assertEquals(ComputeRangePolicy.GridLevel.L1_COARSE, config.gridLevel());
        assertEquals(0.5f, config.stepsMultiplier(), 0.001f);
        assertFalse(config.allocatePhaseField());
        assertFalse(config.allocateMultigrid());
    }

    @Test
    @DisplayName("壓力 ≥ 0.95 → 拒絕（null）")
    void testCriticalPressure_Rejected() {
        // 用量 96%
        mgr.tryRecord(1L, 96L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        int voxels = 100000; // 大 island
        ComputeRangePolicy.ComputeConfig config = ComputeRangePolicy.decide(mgr, voxels);

        assertNull(config, "壓力 ≥ 0.95 時應拒絕大 island");
    }

    @Test
    @DisplayName("adjustSteps 正確套用乘數")
    void testAdjustSteps() {
        var full = new ComputeRangePolicy.ComputeConfig(
                ComputeRangePolicy.GridLevel.L0_FULL, 1.0f, true, true);
        assertEquals(16, ComputeRangePolicy.adjustSteps(16, full));

        var coarse = new ComputeRangePolicy.ComputeConfig(
                ComputeRangePolicy.GridLevel.L1_COARSE, 0.5f, false, false);
        assertEquals(8, ComputeRangePolicy.adjustSteps(16, coarse));

        // null config → 0 steps
        assertEquals(0, ComputeRangePolicy.adjustSteps(16, null));

        // 最少 1 步
        assertEquals(1, ComputeRangePolicy.adjustSteps(1, coarse));
    }
}
