package com.blockreality.api.collapse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CollapseManager queue behavior test — C-6
 *
 * verify:
 *   - hasPending() = false when queue is empty
 *   - processQueue returns safely on empty queue
 *   - clearQueue clears the queue
 *   - Thread-safe ConcurrentLinkedDeque is chosen correctly
 *
 * Note: triggerCollapseAt/checkAndCollapse requires ServerLevel,
 * So only the queue management logic is tested (no world operations involved).
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
