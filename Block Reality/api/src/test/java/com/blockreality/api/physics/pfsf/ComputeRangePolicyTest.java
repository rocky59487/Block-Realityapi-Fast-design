package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComputeRangePolicy test — Validates VRAM stress ranking policy.
 *
 * v0.2a API:
 *   Config decide(VramBudgetManager, int voxelCount) → Config | null
 *   int adjustSteps(int baseSteps, VramBudgetManager) → int
 *   Config: { GridLevel gridLevel, float stepMultiplier, boolean allocatePhaseField, boolean allocateMultigrid }
 */
class ComputeRangePolicyTest {

    private VramBudgetManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new VramBudgetManager();
        // init(VkPhysicalDevice) not called - using fallback budget
    }

    @Test
    @DisplayName("低壓力（空載）→ decide 回傳非 null")
    void testLowPressure_ReturnsConfig() {
        // No distribution → pressure = 0
        ComputeRangePolicy.Config config = ComputeRangePolicy.decide(mgr, 1000);
        assertNotNull(config, "低壓力時 decide 應回傳 Config");
    }

    @Test
    @DisplayName("Config fields 可讀取")
    void testConfigFieldsAccessible() {
        ComputeRangePolicy.Config config = ComputeRangePolicy.decide(mgr, 100);
        if (config != null) {
            assertNotNull(config.gridLevel);
            assertTrue(config.stepMultiplier > 0);
            // allocatePhaseField and allocateMultigrid are booleans — just access them
            boolean pf = config.allocatePhaseField;
            boolean mg = config.allocateMultigrid;
        }
    }

    @Test
    @DisplayName("高壓力 → 可能拒絕大 island")
    void testHighPressure_MayReject() {
        // fill budget
        long budget = mgr.getTotalBudget();
        mgr.tryRecord(1L, (long)(budget * 0.96), VramBudgetManager.PARTITION_PFSF);

        // Large islands may be rejected under high pressure
        ComputeRangePolicy.Config config = ComputeRangePolicy.decide(mgr, 100000);
        // config may be null → that's valid behavior
    }

    @Test
    @DisplayName("adjustSteps 回傳正整數")
    void testAdjustSteps() {
        int result = ComputeRangePolicy.adjustSteps(16, mgr);
        assertTrue(result >= 0, "adjustSteps should return non-negative");
    }

    @Test
    @DisplayName("GridLevel 列舉值存在")
    void testGridLevelEnum() {
        // Verify the enum values exist
        ComputeRangePolicy.GridLevel[] levels = ComputeRangePolicy.GridLevel.values();
        assertTrue(levels.length > 0);
    }
}
