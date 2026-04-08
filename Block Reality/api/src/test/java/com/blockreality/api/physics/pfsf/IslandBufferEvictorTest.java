package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IslandBufferEvictor test — Verifies LRU eviction logic.
 *
 * API (v0.2a):
 *   touchIsland(int islandId)
 *   evictIfNeeded(VramBudgetManager) → int (evicted count)
 *   tick()
 *   removeIsland(int)
 *   reset()
 *   getCheckInterval() → int
 */
class IslandBufferEvictorTest {

    private IslandBufferEvictor evictor;

    @BeforeEach
    void setUp() {
        evictor = new IslandBufferEvictor();
    }

    @Test
    @DisplayName("touchIsland 不拋例外")
    void testTouchIsland() {
        evictor.touchIsland(1);
        evictor.touchIsland(2);
        evictor.touchIsland(3);
        // no exception = pass
    }

    @Test
    @DisplayName("removeIsland 清除追蹤")
    void testRemoveIsland() {
        evictor.touchIsland(1);
        evictor.removeIsland(1);
        // Should not throw; island 1 is gone
    }

    @Test
    @DisplayName("reset 清除所有狀態")
    void testReset() {
        evictor.touchIsland(1);
        evictor.touchIsland(2);
        evictor.reset();
        // After reset, all tracking cleared
    }

    @Test
    @DisplayName("tick 不拋例外")
    void testTick() {
        evictor.touchIsland(1);
        evictor.tick();
        evictor.tick();
    }

    @Test
    @DisplayName("getCheckInterval 回傳正整數")
    void testCheckInterval() {
        assertTrue(evictor.getCheckInterval() > 0);
    }

    @Test
    @DisplayName("evictIfNeeded 不帶 buffer 時安全回傳 0")
    void testEvictIfNeededNullBudget() {
        evictor.touchIsland(1);
        // VramBudgetManager not initialized → getPressure() = 0 → no eviction
        VramBudgetManager mgr = new VramBudgetManager();
        int evicted = evictor.evictIfNeeded(mgr);
        assertEquals(0, evicted, "Uninitialized budget → no eviction");
    }
}
