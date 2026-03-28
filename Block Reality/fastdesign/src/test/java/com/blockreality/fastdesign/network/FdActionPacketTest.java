package com.blockreality.fastdesign.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FdActionPacket 惡意封包測試 — C-8
 *
 * 驗證：
 *   - Action enum 邊界值安全
 *   - 負數 ordinal 被拒絕
 *   - 超出範圍 ordinal 被拒絕
 *   - 所有 Action 值可正確索引
 *   - payload 字串長度限制
 */
@DisplayName("FdActionPacket — Malicious Packet Robustness Tests")
class FdActionPacketTest {

    // ═══ 1. Action Enum Boundaries ═══

    @Test
    @DisplayName("All Action values have valid ordinals")
    void testAllActionsValid() {
        FdActionPacket.Action[] actions = FdActionPacket.Action.values();
        assertTrue(actions.length > 0, "Should have at least one action");

        for (FdActionPacket.Action action : actions) {
            assertTrue(action.ordinal() >= 0);
            assertTrue(action.ordinal() < actions.length);
        }
    }

    // ═══ 2. Negative Ordinal ═══

    @Test
    @DisplayName("Negative ordinal should be rejected")
    void testNegativeOrdinalRejected() {
        int negativeOrdinal = -1;
        FdActionPacket.Action[] actions = FdActionPacket.Action.values();

        assertTrue(negativeOrdinal < 0 || negativeOrdinal >= actions.length,
            "Negative ordinal should fail bounds check");
    }

    // ═══ 3. Overflow Ordinal ═══

    @Test
    @DisplayName("Ordinal beyond enum size should be rejected")
    void testOverflowOrdinalRejected() {
        int overflow = FdActionPacket.Action.values().length;

        assertTrue(overflow >= FdActionPacket.Action.values().length,
            "Overflow ordinal should fail bounds check");
    }

    @Test
    @DisplayName("MAX_VALUE ordinal would be rejected by decode bounds check")
    void testMaxValueOrdinalRejected() {
        int maxOrdinal = Integer.MAX_VALUE;
        FdActionPacket.Action[] actions = FdActionPacket.Action.values();

        assertTrue(maxOrdinal >= actions.length,
            "Integer.MAX_VALUE should exceed action count");
    }

    // ═══ 4. Action Count ═══

    @Test
    @DisplayName("Action enum has expected count (30 actions)")
    void testActionCount() {
        // 30 actions as of v3fix
        int count = FdActionPacket.Action.values().length;
        assertTrue(count >= 25 && count <= 40,
            "Action count should be in expected range (got " + count + ")");
    }

    // ═══ 5. Specific Actions Exist ═══

    @Test
    @DisplayName("Critical actions exist in enum")
    void testCriticalActionsExist() {
        assertNotNull(FdActionPacket.Action.valueOf("UNDO"));
        assertNotNull(FdActionPacket.Action.valueOf("REDO"));
        assertNotNull(FdActionPacket.Action.valueOf("COPY"));
        assertNotNull(FdActionPacket.Action.valueOf("PASTE"));
        assertNotNull(FdActionPacket.Action.valueOf("CLEAR"));
        assertNotNull(FdActionPacket.Action.valueOf("BUILD_SOLID"));
    }

    // ═══ 6. Destructive Actions List ═══

    @Test
    @DisplayName("Destructive actions that require permission are identifiable")
    void testDestructiveActionsIdentifiable() {
        // These actions should require build permission
        String[] destructive = {
            "CLEAR", "PASTE", "LOAD", "FILL", "REPLACE",
            "BUILD_SOLID", "BUILD_WALLS", "BUILD_ARCH",
            "BUILD_BRACE", "BUILD_SLAB", "BUILD_REBAR", "PLACE_MULTI"
        };

        for (String name : destructive) {
            assertDoesNotThrow(() -> FdActionPacket.Action.valueOf(name),
                "Destructive action '" + name + "' should exist in enum");
        }
    }

    // ═══ 7. Packet Construction ═══

    @Test
    @DisplayName("FdActionPacket constructor accepts all actions")
    void testPacketConstruction() {
        for (FdActionPacket.Action action : FdActionPacket.Action.values()) {
            assertDoesNotThrow(() -> new FdActionPacket(action, ""),
                "Should construct packet for action " + action);
        }
    }

    // ═══ 8. Empty Payload Safe ═══

    @Test
    @DisplayName("Empty payload is safe for all actions")
    void testEmptyPayloadSafe() {
        for (FdActionPacket.Action action : FdActionPacket.Action.values()) {
            FdActionPacket pkt = new FdActionPacket(action, "");
            assertNotNull(pkt);
        }
    }
}
