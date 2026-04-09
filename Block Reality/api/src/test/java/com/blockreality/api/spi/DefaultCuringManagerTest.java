package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultCuringManager integration test — v3fix §M4
 *
 * verify:
 *   1. tickCuring() returns the completion position after reaching totalTicks
 *   2. getCuringProgress() increases with tick
 *   3. getActiveCuringCount() correctly reflects the current status
 *   4. removeCuring() removes before completion
 *   5. Repeat startCuring() to overwrite the old curing progress
 *   6. Boundary condition: totalTicks <= 0 should not be accepted
 */
class DefaultCuringManagerTest {

    private DefaultCuringManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultCuringManager();
    }

    // ─── 1. tickCuring returns the completion position ───

    @Test
    void testTickCuringReturnsCompletedPositions() {
        BlockPos pos = new BlockPos(10, 64, 20);
        manager.startCuring(pos, 3);  // 3 ticks completed

        // Tick ​​1, 2: Not completed
        Set<BlockPos> t1 = manager.tickCuring();
        assertTrue(t1.isEmpty(), "Tick 1: should not be complete yet");

        Set<BlockPos> t2 = manager.tickCuring();
        assertTrue(t2.isEmpty(), "Tick 2: should not be complete yet");

        // Tick ​​3: Done
        Set<BlockPos> t3 = manager.tickCuring();
        assertTrue(t3.contains(pos), "Tick 3: position should be in completed set");
        assertEquals(1, t3.size(), "Only one block should complete");
    }

    @Test
    void testTickCuringMultipleBlocksDifferentDurations() {
        BlockPos fast = new BlockPos(0, 0, 0);
        BlockPos slow = new BlockPos(1, 1, 1);

        manager.startCuring(fast, 1);  // 1 tick
        manager.startCuring(slow, 3);  // 3 ticks

        // Tick ​​1: fast completed
        Set<BlockPos> t1 = manager.tickCuring();
        assertTrue(t1.contains(fast), "Fast block should complete at tick 1");
        assertFalse(t1.contains(slow), "Slow block should not complete at tick 1");

        // Tick ​​2: Not completed
        Set<BlockPos> t2 = manager.tickCuring();
        assertTrue(t2.isEmpty(), "No blocks should complete at tick 2");

        // Tick ​​3: slow completed
        Set<BlockPos> t3 = manager.tickCuring();
        assertTrue(t3.contains(slow), "Slow block should complete at tick 3");
    }

    // ─── 2. getCuringProgress increment ───

    @Test
    void testProgressIncrementsCorrectly() {
        BlockPos pos = new BlockPos(5, 5, 5);
        manager.startCuring(pos, 4);  // 4 ticks

        assertEquals(0.0f, manager.getCuringProgress(pos), 0.001f,
            "Initial progress should be 0");

        manager.tickCuring();  // tick 1
        assertEquals(0.25f, manager.getCuringProgress(pos), 0.001f,
            "After 1/4 ticks, progress should be 0.25");

        manager.tickCuring();  // tick 2
        assertEquals(0.5f, manager.getCuringProgress(pos), 0.001f,
            "After 2/4 ticks, progress should be 0.5");

        manager.tickCuring();  // tick 3
        assertEquals(0.75f, manager.getCuringProgress(pos), 0.001f,
            "After 3/4 ticks, progress should be 0.75");

        // tick 4: removed when completed, progress should return to 0
        manager.tickCuring();
        assertEquals(0.0f, manager.getCuringProgress(pos), 0.001f,
            "After completion and removal, progress should be 0");
    }

    @Test
    void testProgressNeverExceedsOne() {
        BlockPos pos = new BlockPos(0, 0, 0);
        manager.startCuring(pos, 2);

        manager.tickCuring();  // tick 1: progress = 0.5
        float p1 = manager.getCuringProgress(pos);
        assertTrue(p1 <= 1.0f, "Progress should not exceed 1.0");

        manager.tickCuring();  // tick 2: completed, removed
        float p2 = manager.getCuringProgress(pos);
        assertTrue(p2 <= 1.0f, "Progress should not exceed 1.0 even after completion");
    }

    @Test
    void testProgressForNonExistentBlockIsZero() {
        BlockPos nonExistent = new BlockPos(999, 999, 999);
        assertEquals(0.0f, manager.getCuringProgress(nonExistent), 0.001f,
            "Progress for non-existent block should be 0");
    }

    // ─── 3. getActiveCuringCount reflects status ───

    @Test
    void testActiveCuringCountReflectsState() {
        assertEquals(0, manager.getActiveCuringCount(),
            "Initial count should be 0");

        BlockPos a = new BlockPos(1, 0, 0);
        BlockPos b = new BlockPos(2, 0, 0);

        manager.startCuring(a, 5);
        assertEquals(1, manager.getActiveCuringCount(),
            "After adding one block, count should be 1");

        manager.startCuring(b, 5);
        assertEquals(2, manager.getActiveCuringCount(),
            "After adding two blocks, count should be 2");

        manager.removeCuring(a);
        assertEquals(1, manager.getActiveCuringCount(),
            "After removing one block, count should be 1");
    }

    @Test
    void testActiveCuringCountAfterCompletion() {
        BlockPos pos = new BlockPos(0, 0, 0);
        manager.startCuring(pos, 1);
        assertEquals(1, manager.getActiveCuringCount());

        manager.tickCuring();  // completes
        assertEquals(0, manager.getActiveCuringCount(),
            "After completion, count should decrease");
    }

    // ─── 4. removeCuring Remove before completion ───

    @Test
    void testRemoveCuringBeforeComplete() {
        BlockPos pos = new BlockPos(10, 10, 10);
        manager.startCuring(pos, 100);

        // Ticked a few times but nowhere near done
        manager.tickCuring();
        manager.tickCuring();
        assertTrue(manager.getCuringProgress(pos) < 1.0f,
            "Should not be complete yet");

        // Remove in advance
        manager.removeCuring(pos);

        assertEquals(0, manager.getActiveCuringCount(),
            "After removal, count should be 0");
        assertEquals(0.0f, manager.getCuringProgress(pos), 0.001f,
            "After removal, progress should be 0");
        assertFalse(manager.isCuringComplete(pos),
            "After removal, should not be marked complete");
    }

    @Test
    void testRemoveNonExistentBlockDoesNotThrow() {
        // Exceptions should not be thrown
        assertDoesNotThrow(() -> manager.removeCuring(new BlockPos(0, 0, 0)),
            "Removing non-existent block should not throw");
    }

    // ─── 5. Repeat startCuring to overwrite the old progress ───

    @Test
    void testRestartCuringOverridesPreviousProgress() {
        BlockPos pos = new BlockPos(3, 3, 3);
        manager.startCuring(pos, 10);

        // Advance 5 ticks
        for (int i = 0; i < 5; i++) manager.tickCuring();
        float midProgress = manager.getCuringProgress(pos);
        assertEquals(0.5f, midProgress, 0.001f);

        // Restart maintenance (new 10 ticks)
        manager.startCuring(pos, 10);

        // Progress reset (since currentTick has been advanced 5 times,
        // startTick is reset to currentTick=5, so new progress = 0/10 = 0)
        assertEquals(0.0f, manager.getCuringProgress(pos), 0.001f,
            "After restart, progress should reset to 0");
        assertEquals(1, manager.getActiveCuringCount(),
            "Should still have exactly 1 active curing");
    }

    // ─── 6. Boundary conditions ───

    @Test
    void testNonPositiveTotalTicksRejected() {
        BlockPos pos = new BlockPos(0, 0, 0);

        manager.startCuring(pos, 0);
        assertEquals(0, manager.getActiveCuringCount(),
            "totalTicks=0 should be rejected");

        manager.startCuring(pos, -5);
        assertEquals(0, manager.getActiveCuringCount(),
            "Negative totalTicks should be rejected");
    }

    @Test
    void testIsCuringCompleteConsistentWithProgress() {
        BlockPos pos = new BlockPos(7, 7, 7);
        manager.startCuring(pos, 2);

        assertFalse(manager.isCuringComplete(pos),
            "Before any ticks, should not be complete");

        manager.tickCuring();
        assertFalse(manager.isCuringComplete(pos),
            "After 1/2 ticks, should not be complete");

        manager.tickCuring();  // completes and removes
        // After completion, block is removed from map
        assertFalse(manager.isCuringComplete(pos),
            "After completion and removal, isCuringComplete returns false (not tracked)");
    }

    @Test
    void testSingleTickCuring() {
        BlockPos pos = new BlockPos(0, 0, 0);
        manager.startCuring(pos, 1);

        Set<BlockPos> completed = manager.tickCuring();
        assertTrue(completed.contains(pos),
            "Block with totalTicks=1 should complete after one tick");
    }
}
