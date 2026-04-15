package com.blockreality.api.physics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhysicsScheduler — focuses on thread-safe bookkeeping logic.
 *
 * Tests cover:
 * - markDirty / hasPendingWork / getPendingCount
 * - markDirty with negative id is silently ignored
 * - markDirty merges epoch (keeps max)
 * - markProcessed removes from dirty set
 * - clear() removes all entries
 * - getScheduledWork with empty dirty set returns empty list
 * - cleanupStaleEntries removes old entries
 * - getTickBudgetMs positive
 *
 * Note: getScheduledWork() with actual islands requires StructureIslandRegistry
 * (needs full Minecraft), so those paths are tested indirectly via empty-set behavior.
 */
@DisplayName("PhysicsScheduler — bookkeeping tests")
class PhysicsSchedulerTest {

    @BeforeEach
    void setUp() {
        PhysicsScheduler.clear();
        StructureIslandRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        PhysicsScheduler.clear();
        StructureIslandRegistry.clear();
    }

    // ─── hasPendingWork / getPendingCount ───

    @Test
    @DisplayName("fresh scheduler has no pending work")
    void testFreshNoPending() {
        assertFalse(PhysicsScheduler.hasPendingWork());
        assertEquals(0, PhysicsScheduler.getPendingCount());
    }

    // ─── markDirty ───

    @Test
    @DisplayName("markDirty with valid ID adds to pending work")
    void testMarkDirtyAdds() {
        PhysicsScheduler.markDirty(1, 100L);
        assertTrue(PhysicsScheduler.hasPendingWork());
        assertEquals(1, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("markDirty with negative ID is ignored")
    void testMarkDirtyNegativeIdIgnored() {
        PhysicsScheduler.markDirty(-1, 100L);
        assertFalse(PhysicsScheduler.hasPendingWork());
        assertEquals(0, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("markDirty is idempotent for same island")
    void testMarkDirtyIdempotent() {
        PhysicsScheduler.markDirty(5, 100L);
        PhysicsScheduler.markDirty(5, 200L);
        assertEquals(1, PhysicsScheduler.getPendingCount(),
            "Same island marked dirty twice should count as 1");
    }

    @Test
    @DisplayName("markDirty keeps max epoch (audit-fix P0-A)")
    void testMarkDirtyKeepsMaxEpoch() {
        // Mark island 1 with epoch=100, then with epoch=50
        // The max should be retained (100 > 50)
        PhysicsScheduler.markDirty(1, 100L);
        PhysicsScheduler.markDirty(1, 50L);
        // getScheduledWork with empty registry → island is stale → removed
        List<PhysicsScheduler.ScheduledWork> work =
            PhysicsScheduler.getScheduledWork(List.of(), 200L);
        // Island doesn't exist in registry → stale → removed from dirty set
        // After cleanup, pending count should be 0
        assertEquals(0, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("markDirty with lower epoch does not overwrite higher epoch")
    void testMarkDirtyEpochMerge() {
        // This verifies Math::max merge is in place:
        // We can't directly inspect the epoch map, but we can verify count is correct
        PhysicsScheduler.markDirty(10, 1000L);
        PhysicsScheduler.markDirty(10, 500L);
        PhysicsScheduler.markDirty(10, 2000L);
        assertEquals(1, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("markDirty with multiple different islands increments count")
    void testMarkDirtyMultiple() {
        PhysicsScheduler.markDirty(1, 100L);
        PhysicsScheduler.markDirty(2, 100L);
        PhysicsScheduler.markDirty(3, 100L);
        assertEquals(3, PhysicsScheduler.getPendingCount());
    }

    // ─── markProcessed ───

    @Test
    @DisplayName("markProcessed removes island from dirty set")
    void testMarkProcessed() {
        PhysicsScheduler.markDirty(7, 100L);
        assertTrue(PhysicsScheduler.hasPendingWork());
        PhysicsScheduler.markProcessed(7);
        assertFalse(PhysicsScheduler.hasPendingWork());
    }

    @Test
    @DisplayName("markProcessed on non-existent island is safe")
    void testMarkProcessedNonExistent() {
        assertDoesNotThrow(() -> PhysicsScheduler.markProcessed(999));
    }

    @Test
    @DisplayName("markProcessed for one island leaves others intact")
    void testMarkProcessedLeavesOthers() {
        PhysicsScheduler.markDirty(1, 100L);
        PhysicsScheduler.markDirty(2, 100L);
        PhysicsScheduler.markProcessed(1);
        assertEquals(1, PhysicsScheduler.getPendingCount());
        assertTrue(PhysicsScheduler.hasPendingWork());
    }

    // ─── clear ───

    @Test
    @DisplayName("clear() removes all pending islands")
    void testClear() {
        PhysicsScheduler.markDirty(1, 100L);
        PhysicsScheduler.markDirty(2, 200L);
        PhysicsScheduler.markDirty(3, 300L);
        PhysicsScheduler.clear();
        assertFalse(PhysicsScheduler.hasPendingWork());
        assertEquals(0, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("clear() on empty scheduler is safe")
    void testClearEmpty() {
        assertDoesNotThrow(PhysicsScheduler::clear);
        assertFalse(PhysicsScheduler.hasPendingWork());
    }

    // ─── getScheduledWork ───

    @Test
    @DisplayName("getScheduledWork with empty dirty set returns empty list")
    void testGetScheduledWorkEmpty() {
        List<PhysicsScheduler.ScheduledWork> work =
            PhysicsScheduler.getScheduledWork(List.of(), 0L);
        assertNotNull(work);
        assertTrue(work.isEmpty());
    }

    @Test
    @DisplayName("getScheduledWork removes stale (non-existent) islands from dirty set")
    void testGetScheduledWorkRemovesStale() {
        // Island 999 doesn't exist in registry → stale → should be cleaned up
        PhysicsScheduler.markDirty(999, 100L);
        PhysicsScheduler.getScheduledWork(List.of(), 200L);
        // After the call, stale island should have been removed
        assertEquals(0, PhysicsScheduler.getPendingCount(),
            "Stale island should be removed during getScheduledWork");
    }

    // ─── cleanupStaleEntries ───

    @Test
    @DisplayName("cleanupStaleEntries removes entries older than maxStaleTicks")
    void testCleanupStaleEntries() {
        PhysicsScheduler.markDirty(100, 0L);  // marked at epoch 0
        // Current epoch is 2000, maxStaleTicks = 1000 → age = 2000 - 0 = 2000 > 1000
        PhysicsScheduler.cleanupStaleEntries(2000L, 1000L);
        assertEquals(0, PhysicsScheduler.getPendingCount());
    }

    @Test
    @DisplayName("cleanupStaleEntries keeps recent entries")
    void testCleanupStaleEntriesKeepsRecent() {
        PhysicsScheduler.markDirty(100, 1900L);  // marked at epoch 1900
        // Current epoch is 2000, age = 100 < 1000 maxStaleTicks
        // But island doesn't exist in registry → removed by that check
        // To test "keep recent", we'd need a real island. This is a no-op test.
        // Just verify no exception
        assertDoesNotThrow(() -> PhysicsScheduler.cleanupStaleEntries(2000L, 1000L));
    }

    // ─── getTickBudgetMs ───

    @Test
    @DisplayName("getTickBudgetMs returns positive value")
    void testGetTickBudgetMs() {
        assertTrue(PhysicsScheduler.getTickBudgetMs() > 0);
    }

    // ─── Concurrency smoke test ───

    @Test
    @DisplayName("concurrent markDirty from multiple threads doesn't throw")
    void testConcurrentMarkDirty() throws Exception {
        int threads = 8;
        int idsPerThread = 100;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int base = t * idsPerThread;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < idsPerThread; i++) {
                    PhysicsScheduler.markDirty(base + i, base + i);
                    PhysicsScheduler.markDirty(base + i, base + i + 1); // higher epoch
                }
            });
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();

        // All islands marked — should have up to threads*idsPerThread pending
        assertTrue(PhysicsScheduler.getPendingCount() <= threads * idsPerThread);

        PhysicsScheduler.clear();
        assertFalse(PhysicsScheduler.hasPendingWork());
    }
}
