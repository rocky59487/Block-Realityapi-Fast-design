package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VramBudgetManager test — Verify alloc/free counters, partition isolation, stress metrics.
 *
 * v0.2a API:
 *   init(VkPhysicalDevice, int usagePercent) — requires a real Vulkan device, not called during testing
 *   tryRecord(long handle, long size, int partition) → boolean
 *   recordFree(long handle)
 *   getTotalUsage() → long
 *   getPartitionUsage(int) → long
 *   getTotalBudget() → long
 *   getPressure() → float
 *   getFreeMemory() → long
 *   isInitialized() → boolean
 */
class VramBudgetManagerTest {

    private VramBudgetManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new VramBudgetManager();
        // init(VkPhysicalDevice) not called - using fallback budget (768MB)
    }

    @Test
    @DisplayName("未初始化時 isInitialized 回傳 false")
    void testNotInitializedByDefault() {
        assertFalse(mgr.isInitialized());
    }

    @Test
    @DisplayName("CRITICAL: alloc→free 計數器歸零")
    void testAllocFreeCycleCounterReturnsToZero() {
        long size = 1024 * 1024; // 1 MB
        long handle = 42L;

        mgr.tryRecord(handle, size, VramBudgetManager.PARTITION_PFSF);
        assertEquals(size, mgr.getTotalUsage());

        mgr.recordFree(handle);
        assertEquals(0, mgr.getTotalUsage(), "計數器應歸零");
    }

    @Test
    @DisplayName("多次 alloc/free 循環後計數器準確")
    void testMultipleAllocFreeCycles() {
        long size = 512 * 1024;
        for (int i = 0; i < 100; i++) {
            mgr.tryRecord(1000 + i, size, VramBudgetManager.PARTITION_PFSF);
        }
        assertEquals(100L * size, mgr.getTotalUsage());

        for (int i = 0; i < 100; i++) {
            mgr.recordFree(1000 + i);
        }
        assertEquals(0, mgr.getTotalUsage());
    }

    @Test
    @DisplayName("壓力指標正確反映使用量")
    void testPressureMetrics() {
        assertEquals(0f, mgr.getPressure(), 0.001f);

        long budget = mgr.getTotalBudget();
        if (budget > 0) {
            long half = budget / 2;
            mgr.tryRecord(1L, half, VramBudgetManager.PARTITION_PFSF);
            assertTrue(mgr.getPressure() > 0);
            assertTrue(mgr.getFreeMemory() > 0);
        }
    }

    @Test
    @DisplayName("釋放不存在的 buffer 不拋例外")
    void testFreeUnknownBufferIsSafe() {
        assertDoesNotThrow(() -> mgr.recordFree(99999L));
        assertEquals(0, mgr.getTotalUsage());
    }

    @Test
    @DisplayName("不同分區獨立追蹤")
    void testPartitionIsolation() {
        mgr.tryRecord(1L, 1024, VramBudgetManager.PARTITION_PFSF);
        mgr.tryRecord(2L, 2048, VramBudgetManager.PARTITION_FLUID);
        mgr.tryRecord(3L, 512, VramBudgetManager.PARTITION_OTHER);

        assertEquals(1024, mgr.getPartitionUsage(VramBudgetManager.PARTITION_PFSF));
        assertEquals(2048, mgr.getPartitionUsage(VramBudgetManager.PARTITION_FLUID));
        assertEquals(512, mgr.getPartitionUsage(VramBudgetManager.PARTITION_OTHER));
        assertEquals(1024 + 2048 + 512, mgr.getTotalUsage());

        mgr.recordFree(1L);
        assertEquals(0, mgr.getPartitionUsage(VramBudgetManager.PARTITION_PFSF));
        assertEquals(2048 + 512, mgr.getTotalUsage());
    }

    @Test
    @DisplayName("getTotalBudget 回傳正數（fallback 768MB）")
    void testTotalBudgetPositive() {
        assertTrue(mgr.getTotalBudget() > 0);
    }
}
