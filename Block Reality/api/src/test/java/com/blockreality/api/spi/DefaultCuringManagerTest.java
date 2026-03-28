package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultCuringManager 整合測試 — v3fix §M4
 *
 * 驗證：
 *   1. tickCuring() 在到達 totalTicks 後回傳完成位置
 *   2. getCuringProgress() 隨 tick 遞增
 *   3. getActiveCuringCount() 正確反映當前狀態
 *   4. removeCuring() 在完成前移除
 *   5. 重複 startCuring() 覆蓋舊的養護進度
 *   6. 邊界條件：totalTicks <= 0 不應被接受
 */
class DefaultCuringManagerTest {

    private DefaultCuringManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultCuringManager();
    }

    // ─── 1. tickCuring 回傳完成位置 ───

    @Test
    void testTickCuringReturnsCompletedPositions() {
        BlockPos pos = new BlockPos(10, 64, 20);
        manager.startCuring(pos, 3);  // 3 ticks 完成

        // Tick 1, 2: 未完成
        Set<BlockPos> t1 = manager.tickCuring();
        assertTrue(t1.isEmpty(), "Tick 1: should not be complete yet");

        Set<BlockPos> t2 = manager.tickCuring();
        assertTrue(t2.isEmpty(), "Tick 2: should not be complete yet");

        // Tick 3: 完成
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

        // Tick 1: fast 完成
        Set<BlockPos> t1 = manager.tickCuring();
        assertTrue(t1.contains(fast), "Fast block should complete at tick 1");
        assertFalse(t1.contains(slow), "Slow block should not complete at tick 1");

        // Tick 2: 無完成
        Set<BlockPos> t2 = manager.tickCuring();
        assertTrue(t2.isEmpty(), "No blocks should complete at tick 2");

        // Tick 3: slow 完成
        Set<BlockPos> t3 = manager.tickCuring();
        assertTrue(t3.contains(slow), "Slow block should complete at tick 3");
    }

    // ─── 2. getCuringProgress 遞增 ───

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

        // tick 4: 完成後移除，progress 應回到 0
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

    // ─── 3. getActiveCuringCount 反映狀態 ───

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

    // ─── 4. removeCuring 在完成前移除 ───

    @Test
    void testRemoveCuringBeforeComplete() {
        BlockPos pos = new BlockPos(10, 10, 10);
        manager.startCuring(pos, 100);

        // Tick 幾次但遠未完成
        manager.tickCuring();
        manager.tickCuring();
        assertTrue(manager.getCuringProgress(pos) < 1.0f,
            "Should not be complete yet");

        // 提前移除
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
        // 不應拋出例外
        assertDoesNotThrow(() -> manager.removeCuring(new BlockPos(0, 0, 0)),
            "Removing non-existent block should not throw");
    }

    // ─── 5. 重複 startCuring 覆蓋舊進度 ───

    @Test
    void testRestartCuringOverridesPreviousProgress() {
        BlockPos pos = new BlockPos(3, 3, 3);
        manager.startCuring(pos, 10);

        // 推進 5 tick
        for (int i = 0; i < 5; i++) manager.tickCuring();
        float midProgress = manager.getCuringProgress(pos);
        assertEquals(0.5f, midProgress, 0.001f);

        // 重新開始養護（新的 10 ticks）
        manager.startCuring(pos, 10);

        // 進度重置（由於 currentTick 已經推進了 5 次，
        // startTick 被重置為 currentTick=5，所以新的 progress = 0/10 = 0）
        assertEquals(0.0f, manager.getCuringProgress(pos), 0.001f,
            "After restart, progress should reset to 0");
        assertEquals(1, manager.getActiveCuringCount(),
            "Should still have exactly 1 active curing");
    }

    // ─── 6. 邊界條件 ───

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
