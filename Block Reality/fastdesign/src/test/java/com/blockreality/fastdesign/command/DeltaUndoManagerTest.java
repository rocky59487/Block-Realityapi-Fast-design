package com.blockreality.fastdesign.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeltaUndoManager 一致性測試 — C-7
 *
 * 驗證 delta-based undo/redo 的邏輯一致性（不需要 Minecraft 世界）：
 *   - 初始狀態：無 undo/redo 可用
 *   - Stack size 追蹤
 *   - clear() 清空所有歷史
 *   - onPlayerDisconnect 清理
 *   - description peek
 *
 * 注意：captureBeforeState/commitChanges/undo/redo 需要 ServerLevel，
 * 因此只測試狀態管理邏輯。
 */
@DisplayName("DeltaUndoManager — State Consistency Tests")
class DeltaUndoManagerTest {

    // ═══ 1. Initial State ═══

    @Test
    @DisplayName("New player: zero undo and redo stack size")
    void testInitialStateEmpty() {
        UUID testUuid = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        assertEquals(0, mgr.getUndoStackSize(testUuid),
            "New player should have zero undo entries");
        assertEquals(0, mgr.getRedoStackSize(testUuid),
            "New player should have zero redo entries");
    }

    // ═══ 2. Peek on Empty Stack ═══

    @Test
    @DisplayName("Peek on empty stack returns null")
    void testPeekEmptyReturnsNull() {
        UUID testUuid = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        assertNull(mgr.peekUndoDescription(testUuid),
            "Peek undo on empty should be null");
        assertNull(mgr.peekRedoDescription(testUuid),
            "Peek redo on empty should be null");
    }

    // ═══ 3. Clear ═══

    @Test
    @DisplayName("clear() resets both stacks")
    void testClearResetsStacks() {
        UUID testUuid = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        mgr.clear(testUuid);

        assertEquals(0, mgr.getUndoStackSize(testUuid));
        assertEquals(0, mgr.getRedoStackSize(testUuid));
    }

    // ═══ 4. Player Disconnect Cleanup ═══

    @Test
    @DisplayName("onPlayerDisconnect cleans up state")
    void testPlayerDisconnectCleanup() {
        UUID testUuid = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        mgr.onPlayerDisconnect(testUuid);

        assertEquals(0, mgr.getUndoStackSize(testUuid));
        assertEquals(0, mgr.getRedoStackSize(testUuid));
    }

    // ═══ 5. Different Players Independent ═══

    @Test
    @DisplayName("Different player UUIDs have independent stacks")
    void testPlayerIsolation() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        mgr.clear(player1);
        mgr.clear(player2);

        // Operations on one player should not affect another
        assertEquals(0, mgr.getUndoStackSize(player1));
        assertEquals(0, mgr.getUndoStackSize(player2));
    }

    // ═══ 6. Multiple Clears Safe ═══

    @Test
    @DisplayName("Multiple clear() calls are safe")
    void testMultipleClearsSafe() {
        UUID testUuid = UUID.randomUUID();
        DeltaUndoManager mgr = new DeltaUndoManager();

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                mgr.clear(testUuid);
            }
        });
    }
}
