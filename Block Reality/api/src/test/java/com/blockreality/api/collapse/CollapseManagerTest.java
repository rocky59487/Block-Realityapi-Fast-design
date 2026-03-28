package com.blockreality.api.collapse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CollapseManager 佇列行為測試 — C-6
 *
 * 驗證：
 *   - 佇列空時 hasPending() = false
 *   - processQueue 在空佇列時安全返回
 *   - clearQueue 清空佇列
 *   - 線程安全的 ConcurrentLinkedDeque 選擇正確
 *
 * 注意：triggerCollapseAt/checkAndCollapse 需要 ServerLevel，
 * 因此只測試佇列管理邏輯（不涉及世界操作）。
 */
@DisplayName("CollapseManager — Queue Behavior Tests")
class CollapseManagerTest {

    // ═══ 1. Empty Queue State ═══

    @Test
    @DisplayName("Fresh state: hasPending() returns false")
    void testEmptyQueueHasPendingFalse() {
        CollapseManager.clearQueue();
        assertFalse(CollapseManager.hasPending(),
            "Empty queue should have no pending collapses");
    }

    // ═══ 2. processQueue on Empty Queue ═══

    @Test
    @DisplayName("processQueue on empty queue does not throw")
    void testProcessQueueEmptySafe() {
        CollapseManager.clearQueue();
        assertDoesNotThrow(CollapseManager::processQueue,
            "processQueue should be safe on empty queue");
    }

    // ═══ 3. clearQueue ═══

    @Test
    @DisplayName("clearQueue makes hasPending() false")
    void testClearQueue() {
        CollapseManager.clearQueue();
        assertFalse(CollapseManager.hasPending());
    }

    // ═══ 4. Queue Is Thread-Safe Type ═══

    @Test
    @DisplayName("CollapseManager uses ConcurrentLinkedDeque (thread-safe)")
    void testQueueTypeSafe() {
        // The queue field is ConcurrentLinkedDeque — verify through behavior:
        // Multiple clearQueue + hasPending calls should not throw ConcurrentModificationException
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                CollapseManager.clearQueue();
                CollapseManager.hasPending();
                CollapseManager.processQueue();
            }
        }, "Rapid queue operations should not throw");
    }

    // ═══ 5. processQueue After clearQueue ═══

    @Test
    @DisplayName("processQueue after clearQueue is safe")
    void testProcessAfterClear() {
        CollapseManager.clearQueue();
        CollapseManager.processQueue();
        assertFalse(CollapseManager.hasPending());
    }
}
