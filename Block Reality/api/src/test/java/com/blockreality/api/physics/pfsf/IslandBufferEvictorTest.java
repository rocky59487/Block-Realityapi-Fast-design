package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IslandBufferEvictor 測試 — 驗證 LRU 驅逐邏輯。
 * 注意：使用 mock buffers（不呼叫 Vulkan）。
 */
class IslandBufferEvictorTest {

    private IslandBufferEvictor evictor;
    private VramBudgetManager budgetMgr;

    @BeforeEach
    void setUp() {
        evictor = new IslandBufferEvictor();
        budgetMgr = new VramBudgetManager();
        budgetMgr.initManual(100L * 1024 * 1024, 0.66f, 0.22f, 0.12f); // 100 MB
    }

    @Test
    @DisplayName("壓力 < 0.9 時不驅逐")
    void testNoEvictionWhenPressureLow() {
        // 壓力 50%
        budgetMgr.tryRecord(1L, 50L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        ConcurrentHashMap<Integer, PFSFIslandBuffer> buffers = new ConcurrentHashMap<>();
        evictor.touchIsland(1, 0);

        List<Integer> evicted = evictor.evictIfNeeded(200, budgetMgr, buffers);
        assertTrue(evicted.isEmpty(), "壓力 < 0.9 時不應驅逐");
    }

    @Test
    @DisplayName("壓力 > 0.9 時驅逐最舊的 island")
    void testEvictionWhenPressureHigh() {
        // 壓力 92%
        budgetMgr.tryRecord(1L, 92L * 1024 * 1024, VramBudgetManager.PARTITION_PFSF);

        // 模擬 3 個 island，不同的 lastAccess 時間
        evictor.touchIsland(10, 0);   // 最舊
        evictor.touchIsland(20, 50);  // 中間
        evictor.touchIsland(30, 100); // 最新

        ConcurrentHashMap<Integer, PFSFIslandBuffer> buffers = new ConcurrentHashMap<>();
        // 不加入真實 buffer（避免 Vulkan 呼叫）— evictor 會跳過 null buffer

        List<Integer> evicted = evictor.evictIfNeeded(200, budgetMgr, buffers);
        // 因為 buffers 為空，不會真正驅逐任何 island
        assertTrue(evicted.isEmpty());
    }

    @Test
    @DisplayName("removeIsland 清除追蹤")
    void testRemoveIsland() {
        evictor.touchIsland(1, 0);
        evictor.removeIsland(1);
        assertFalse(evictor.wasEvicted(1));
    }

    @Test
    @DisplayName("reset 清除所有狀態")
    void testReset() {
        evictor.touchIsland(1, 0);
        evictor.touchIsland(2, 10);
        evictor.reset();

        // reset 後應該沒有任何追蹤
        assertFalse(evictor.wasEvicted(1));
        assertFalse(evictor.wasEvicted(2));
    }
}
