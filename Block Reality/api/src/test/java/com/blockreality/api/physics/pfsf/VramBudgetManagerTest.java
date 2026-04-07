package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VramBudgetManager 測試 — 驗證 CRITICAL bug 修復（alloc→free 計數器歸零）。
 */
class VramBudgetManagerTest {

    private VramBudgetManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new VramBudgetManager();
        // 手動初始化：1GB 總預算
        mgr.initManual(1024L * 1024 * 1024, 0.66f, 0.22f, 0.12f);
    }

    @Test
    @DisplayName("CRITICAL: alloc→free 計數器歸零（修復舊版 freeBuffer 不遞減 bug）")
    void testAllocFreeCycleCounterReturnsToZero() {
        long size = 1024 * 1024; // 1 MB
        long bufferHandle = 42L;

        // 分配
        assertTrue(mgr.tryRecord(bufferHandle, size, VramBudgetManager.PARTITION_PFSF));
        assertEquals(size, mgr.getTotalUsed());

        // 釋放 — CRITICAL: 必須遞減計數器
        mgr.recordFree(bufferHandle);
        assertEquals(0, mgr.getTotalUsed(), "計數器應歸零！舊版不遞減導致 VRAM 單調成長");
    }

    @Test
    @DisplayName("多次 alloc/free 循環後計數器保持準確")
    void testMultipleAllocFreeCycles() {
        long size = 512 * 1024; // 512 KB
        for (int i = 0; i < 100; i++) {
            long handle = 1000 + i;
            assertTrue(mgr.tryRecord(handle, size, VramBudgetManager.PARTITION_PFSF));
        }
        assertEquals(100L * size, mgr.getTotalUsed());

        // 釋放全部
        for (int i = 0; i < 100; i++) {
            mgr.recordFree(1000 + i);
        }
        assertEquals(0, mgr.getTotalUsed(), "100 次 alloc/free 後計數器應歸零");
    }

    @Test
    @DisplayName("分區預算檢查：超限應回傳 false")
    void testPartitionBudgetExceeded() {
        // PFSF 分區 = 1GB × 66% ≈ 675 MB
        long pfsfBudget = (long) (1024L * 1024 * 1024 * 0.66);

        // 分配接近上限
        assertTrue(mgr.tryRecord(1L, pfsfBudget - 1024, VramBudgetManager.PARTITION_PFSF));

        // 再分配超過上限
        assertFalse(mgr.tryRecord(2L, 2048, VramBudgetManager.PARTITION_PFSF));
    }

    @Test
    @DisplayName("壓力指標正確反映使用量")
    void testPressureMetrics() {
        assertEquals(0f, mgr.getPressure(), 0.001f);

        long half = mgr.getTotalBudget() / 2;
        mgr.tryRecord(1L, half, VramBudgetManager.PARTITION_PFSF);

        assertEquals(0.5f, mgr.getPressure(), 0.01f);
        assertTrue(mgr.getFreeMemory() > 0);
    }

    @Test
    @DisplayName("釋放不存在的 buffer 不應拋例外")
    void testFreeUnknownBufferIsSafe() {
        // 不應拋例外（staging buffer 等不追蹤的分配）
        assertDoesNotThrow(() -> mgr.recordFree(99999L));
        assertEquals(0, mgr.getTotalUsed());
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
        assertEquals(1024 + 2048 + 512, mgr.getTotalUsed());

        // 釋放 PFSF
        mgr.recordFree(1L);
        assertEquals(0, mgr.getPartitionUsage(VramBudgetManager.PARTITION_PFSF));
        assertEquals(2048 + 512, mgr.getTotalUsed());
    }

    @Test
    @DisplayName("reset 清除所有狀態")
    void testReset() {
        mgr.tryRecord(1L, 1024, VramBudgetManager.PARTITION_PFSF);
        mgr.tryRecord(2L, 2048, VramBudgetManager.PARTITION_FLUID);

        mgr.reset();

        assertEquals(0, mgr.getTotalUsed());
        assertEquals(0, mgr.getPartitionUsage(VramBudgetManager.PARTITION_PFSF));
        assertEquals(0, mgr.getPartitionUsage(VramBudgetManager.PARTITION_FLUID));
    }
}
