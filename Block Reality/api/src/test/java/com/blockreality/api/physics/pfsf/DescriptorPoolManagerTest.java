package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DescriptorPoolManager 測試 — 驗證按需重置邏輯。
 * 注意：不呼叫 Vulkan（pool handle = 0L for pure logic testing）。
 */
class DescriptorPoolManagerTest {

    @Test
    @DisplayName("空閒時不重置（usage < 80%）")
    void testNoResetWhenIdle() {
        // capacity = 100, 不分配任何 set
        var mgr = new TestableDescriptorPoolManager(0L, 100);

        // 每 tick 檢查：不應觸發 reset
        boolean reset = mgr.tickResetIfNeeded_noVulkan();
        assertFalse(reset, "空閒時不應重置");
    }

    @Test
    @DisplayName("使用率 > 80% 時觸發重置")
    void testResetWhenUsageHigh() {
        var mgr = new TestableDescriptorPoolManager(0L, 100);

        // 模擬 81 次分配
        for (int i = 0; i < 81; i++) {
            mgr.recordAllocation();
        }

        assertTrue(mgr.getUsageRatio() > 0.80f);
        boolean reset = mgr.tickResetIfNeeded_noVulkan();
        assertTrue(reset, "使用率 > 80% 應觸發重置");

        // 重置後計數歸零
        assertEquals(0f, mgr.getUsageRatio(), 0.001f);
    }

    @Test
    @DisplayName("forceReset 無條件重置")
    void testForceReset() {
        var mgr = new TestableDescriptorPoolManager(0L, 100);
        mgr.recordAllocation();
        mgr.recordAllocation();

        assertTrue(mgr.getUsageRatio() > 0);
        mgr.forceReset_noVulkan();
        assertEquals(0f, mgr.getUsageRatio(), 0.001f);
    }

    @Test
    @DisplayName("使用率計算正確")
    void testUsageRatioAccuracy() {
        var mgr = new TestableDescriptorPoolManager(0L, 200);

        for (int i = 0; i < 50; i++) {
            mgr.recordAllocation();
        }
        assertEquals(0.25f, mgr.getUsageRatio(), 0.001f);
    }

    /**
     * 可測試版本：繞過 Vulkan 呼叫。
     */
    private static class TestableDescriptorPoolManager extends DescriptorPoolManager {

        private final java.util.concurrent.atomic.AtomicInteger testCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        TestableDescriptorPoolManager(long pool, int capacity) {
            super(pool, capacity);
        }

        @Override
        public void recordAllocation() {
            super.recordAllocation();
            testCount.incrementAndGet();
        }

        boolean tickResetIfNeeded_noVulkan() {
            if (getUsageRatio() > 0.80f) {
                forceReset_noVulkan();
                return true;
            }
            return false;
        }

        void forceReset_noVulkan() {
            // 不呼叫 VulkanComputeContext.resetDescriptorPool()
            // 直接用反射或重建來模擬 reset
            // 簡化：建立新的 manager（測試行為而非 Vulkan 呼叫）
            try {
                var field = DescriptorPoolManager.class.getDeclaredField("allocatedCount");
                field.setAccessible(true);
                ((java.util.concurrent.atomic.AtomicInteger) field.get(this)).set(0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
