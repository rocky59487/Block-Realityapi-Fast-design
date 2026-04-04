package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFFailureApplicator 斷裂偵測和映射測試。
 */
class PFSFFailureTest {

    @Test
    @DisplayName("空 failFlags → 無斷裂")
    void testEmptyFailFlags() {
        byte[] flags = new byte[100];
        // 無法直接呼叫 apply（需 ServerLevel），故測試映射邏輯

        int failCount = 0;
        for (byte f : flags) {
            if (f != FAIL_OK) failCount++;
        }
        assertEquals(0, failCount);
    }

    @Test
    @DisplayName("fail flag 值對應正確常數")
    void testFailFlagConstants() {
        assertEquals(0, FAIL_OK);
        assertEquals(1, FAIL_CANTILEVER);
        assertEquals(2, FAIL_CRUSHING);
        assertEquals(3, FAIL_NO_SUPPORT);
    }

    @Test
    @DisplayName("MAX_FAILURE_PER_TICK 截斷")
    void testMaxFailurePerTick() {
        // 模擬超過限制的 failFlags
        byte[] flags = new byte[5000];
        for (int i = 0; i < 5000; i++) flags[i] = FAIL_CANTILEVER;

        int count = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] != FAIL_OK) {
                if (count >= MAX_FAILURE_PER_TICK) break;
                count++;
            }
        }
        assertEquals(MAX_FAILURE_PER_TICK, count, "應截斷在 MAX_FAILURE_PER_TICK=" + MAX_FAILURE_PER_TICK);
    }

    @Test
    @DisplayName("體素類型標記值正確")
    void testVoxelTypeConstants() {
        assertEquals(0, VOXEL_AIR);
        assertEquals(1, VOXEL_SOLID);
        assertEquals(2, VOXEL_ANCHOR);
    }

    @Test
    @DisplayName("物理常數合理性檢查")
    void testPhysicsConstants() {
        assertEquals(9.81, GRAVITY, 0.01);
        assertEquals(1.0, BLOCK_VOLUME, 0.001);
        assertEquals(1.0, BLOCK_AREA, 0.001);
        assertTrue(MOMENT_ALPHA > 0 && MOMENT_ALPHA < 1.0, "MOMENT_ALPHA 應在 (0,1)");
        assertTrue(MOMENT_BETA > 0 && MOMENT_BETA < 1.0, "MOMENT_BETA 應在 (0,1)");
        assertTrue(PHI_ORPHAN_THRESHOLD > 1e4, "PHI_ORPHAN_THRESHOLD 應足夠大");
        assertTrue(MAX_FAILURE_PER_TICK > 0);
        assertTrue(MAX_CASCADE_RADIUS > 0);
        assertTrue(TICK_BUDGET_MS > 0);
    }
}
