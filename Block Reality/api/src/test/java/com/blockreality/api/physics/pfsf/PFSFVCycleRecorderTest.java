package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFVCycleRecorder 工具方法與排程邏輯測試。
 */
class PFSFVCycleRecorderTest {

    @Test
    @DisplayName("ceilDiv：精確整除")
    void testCeilDivExact() {
        assertEquals(4, PFSFVCycleRecorder.ceilDiv(16, 4));
        assertEquals(1, PFSFVCycleRecorder.ceilDiv(256, 256));
    }

    @Test
    @DisplayName("ceilDiv：非整除向上取整")
    void testCeilDivRoundsUp() {
        assertEquals(5, PFSFVCycleRecorder.ceilDiv(17, 4));
        assertEquals(2, PFSFVCycleRecorder.ceilDiv(257, 256));
        assertEquals(1, PFSFVCycleRecorder.ceilDiv(1, 256));
    }

    @Test
    @DisplayName("ceilDiv：a=0 → 0")
    void testCeilDivZero() {
        assertEquals(0, PFSFVCycleRecorder.ceilDiv(0, 8));
    }

    @Test
    @DisplayName("V-Cycle 觸發條件：MG_INTERVAL=4，step>0 且是 4 的倍數")
    void testVCycleTriggerCondition() {
        // V-Cycle should trigger at step 4, 8, 12, ... (k > 0 && k % MG_INTERVAL == 0)
        for (int k = 0; k < 20; k++) {
            boolean shouldVCycle = k > 0 && k % PFSFConstants.MG_INTERVAL == 0;
            if (k == 4 || k == 8 || k == 12 || k == 16) {
                assertTrue(shouldVCycle, "V-Cycle should trigger at step " + k);
            } else {
                assertFalse(shouldVCycle || k == 0,
                        "V-Cycle should NOT trigger at step " + k + " (only at multiples of " + PFSFConstants.MG_INTERVAL + " > 0)");
            }
        }
    }

    @Test
    @DisplayName("Workgroup 計算：256 體素需要 ceilDiv(8,8)×ceilDiv(4,8)×ceilDiv(8,4) = 1×1×2")
    void testWorkgroupCalculation() {
        int Lx = 8, Ly = 4, Lz = 8;
        int gx = PFSFVCycleRecorder.ceilDiv(Lx, PFSFConstants.WG_X);
        int gy = PFSFVCycleRecorder.ceilDiv(Ly, PFSFConstants.WG_Y);
        int gz = PFSFVCycleRecorder.ceilDiv(Lz, PFSFConstants.WG_Z);

        assertEquals(1, gx, "WG_X=8, Lx=8 → 1 group");
        assertEquals(1, gy, "WG_Y=8, Ly=4 → 1 group");
        assertEquals(2, gz, "WG_Z=4, Lz=8 → 2 groups");
    }

    @Test
    @DisplayName("大型 island workgroup：100×200×50")
    void testLargeIslandWorkgroups() {
        int Lx = 100, Ly = 200, Lz = 50;
        int gx = PFSFVCycleRecorder.ceilDiv(Lx, PFSFConstants.WG_X); // 100/8 = 13
        int gy = PFSFVCycleRecorder.ceilDiv(Ly, PFSFConstants.WG_Y); // 200/8 = 25
        int gz = PFSFVCycleRecorder.ceilDiv(Lz, PFSFConstants.WG_Z); // 50/4  = 13

        assertEquals(13, gx);
        assertEquals(25, gy);
        assertEquals(13, gz);
        // Total dispatched workgroups
        assertEquals(13 * 25 * 13, gx * gy * gz);
    }
}
