package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DescriptorPoolManager 測試 — 驗證按需重置邏輯。
 * 注意：不呼叫 Vulkan（pool handle = 0L for pure logic testing）。
 * DescriptorPoolManager 是 final，使用直接實例化 + 反射。
 */
class DescriptorPoolManagerTest {

    private DescriptorPoolManager create(int capacity) {
        return new DescriptorPoolManager(0L, capacity, "test");
    }

    @Test
    @DisplayName("空閒時不重置（usage < 80%）")
    void testNoResetWhenIdle() {
        var mgr = create(100);
        // 不分配 → usage = 0 → 不應觸發 reset
        float ratio = mgr.getUsageRatio();
        assertEquals(0f, ratio, 0.001f);
    }

    @Test
    @DisplayName("notifyAllocated 增加使用率")
    void testNotifyAllocatedIncrementsUsage() {
        var mgr = create(100);
        mgr.notifyAllocated(50);
        assertEquals(0.50f, mgr.getUsageRatio(), 0.01f);
    }

    @Test
    @DisplayName("notifyAllocated 單次呼叫")
    void testNotifyAllocatedSingle() {
        var mgr = create(200);
        for (int i = 0; i < 50; i++) {
            mgr.notifyAllocated();
        }
        assertEquals(0.25f, mgr.getUsageRatio(), 0.01f);
    }

    @Test
    @DisplayName("getAllocatedSets 正確計數")
    void testGetAllocatedSets() {
        var mgr = create(100);
        mgr.notifyAllocated(30);
        assertEquals(30, mgr.getAllocatedSets());
    }

    @Test
    @DisplayName("getPool 回傳建構時的 pool handle")
    void testGetPool() {
        var mgr = new DescriptorPoolManager(42L, 100, "test");
        assertEquals(42L, mgr.getPool());
    }

    @Test
    @DisplayName("destroy 可安全多次呼叫")
    void testDestroyIdempotent() {
        var mgr = create(10);
        mgr.destroy();
        mgr.destroy(); // should not throw
    }
}
