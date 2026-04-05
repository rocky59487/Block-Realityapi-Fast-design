package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFConstants 物理常數與 GPU 工作組參數完整性測試。
 *
 * <p>確保所有常數在合理物理範圍內，且 GPU 參數滿足 Vulkan 最低保證。</p>
 */
class PFSFConstantsTest {

    @Test
    @DisplayName("重力常數 = 9.81 m/s²")
    void testGravity() {
        assertEquals(9.81f, GRAVITY, 1e-3f);
    }

    @Test
    @DisplayName("Moment 修正參數：α=0.20, β=0.10 在合理範圍")
    void testMomentParameters() {
        assertTrue(MOMENT_ALPHA > 0 && MOMENT_ALPHA < 1.0f,
                "MOMENT_ALPHA should be in (0, 1): " + MOMENT_ALPHA);
        assertTrue(MOMENT_BETA > 0 && MOMENT_BETA < 1.0f,
                "MOMENT_BETA should be in (0, 1): " + MOMENT_BETA);
    }

    @Test
    @DisplayName("Failure 閾值：PHI_ORPHAN = 1e6")
    void testOrphanThreshold() {
        assertEquals(1e6f, PHI_ORPHAN_THRESHOLD, 1e2f);
        assertTrue(PHI_ORPHAN_THRESHOLD > 0);
    }

    @Test
    @DisplayName("MAX_FAILURE_PER_TICK = 2000，防止單 tick 崩塌爆炸")
    void testMaxFailurePerTick() {
        assertTrue(MAX_FAILURE_PER_TICK > 0 && MAX_FAILURE_PER_TICK <= 10000,
                "MAX_FAILURE_PER_TICK should be reasonable: " + MAX_FAILURE_PER_TICK);
    }

    @Test
    @DisplayName("GPU 工作組：WG_X × WG_Y × WG_Z ≤ 256（Vulkan 最低保證）")
    void testWorkgroupSize() {
        int total = WG_X * WG_Y * WG_Z;
        assertTrue(total <= 256,
                "Workgroup size must not exceed Vulkan minimum guarantee of 256: " + total);
        assertTrue(total > 0);
    }

    @Test
    @DisplayName("WG_SCAN = 256，failure scan 1D 工作組")
    void testScanWorkgroup() {
        assertEquals(256, WG_SCAN);
    }

    @Test
    @DisplayName("Chebyshev 參數：WARMUP_STEPS=8, MAX_OMEGA<2.0, SAFETY_MARGIN<1.0")
    void testChebyshevParameters() {
        assertTrue(WARMUP_STEPS >= 1 && WARMUP_STEPS <= 32);
        assertTrue(MAX_OMEGA > 1.0f && MAX_OMEGA < 2.0f,
                "MAX_OMEGA must be in (1, 2): " + MAX_OMEGA);
        assertTrue(SAFETY_MARGIN > 0.5f && SAFETY_MARGIN < 1.0f,
                "SAFETY_MARGIN must be in (0.5, 1): " + SAFETY_MARGIN);
    }

    @Test
    @DisplayName("Damping factor ∈ (0.9, 1.0)，確保能量衰減但不會太快")
    void testDampingFactor() {
        assertTrue(DAMPING_FACTOR > 0.9f && DAMPING_FACTOR < 1.0f,
                "DAMPING_FACTOR should be close to 1: " + DAMPING_FACTOR);
    }

    @Test
    @DisplayName("Divergence ratio > 1.0，表示增長才觸發")
    void testDivergenceRatio() {
        assertTrue(DIVERGENCE_RATIO > 1.0f,
                "DIVERGENCE_RATIO must be > 1.0: " + DIVERGENCE_RATIO);
    }

    @Test
    @DisplayName("方向索引完整性：6 個方向 0-5 互不重複")
    void testDirectionIndices() {
        int[] dirs = {DIR_NEG_X, DIR_POS_X, DIR_NEG_Y, DIR_POS_Y, DIR_NEG_Z, DIR_POS_Z};
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int d : dirs) {
            assertTrue(d >= 0 && d < 6, "Direction index out of range: " + d);
            assertTrue(unique.add(d), "Duplicate direction index: " + d);
        }
        assertEquals(6, unique.size());
    }

    @Test
    @DisplayName("Voxel type 互不重複：AIR=0, SOLID=1, ANCHOR=2")
    void testVoxelTypes() {
        assertNotEquals(VOXEL_AIR, VOXEL_SOLID);
        assertNotEquals(VOXEL_AIR, VOXEL_ANCHOR);
        assertNotEquals(VOXEL_SOLID, VOXEL_ANCHOR);
    }

    @Test
    @DisplayName("Failure flag 互不重複且 > 0（除 FAIL_OK=0）")
    void testFailureFlags() {
        assertEquals(0, FAIL_OK);
        assertTrue(FAIL_CANTILEVER > 0);
        assertTrue(FAIL_CRUSHING > 0);
        assertTrue(FAIL_NO_SUPPORT > 0);
        assertTrue(FAIL_TENSION > 0);

        java.util.Set<Byte> flags = new java.util.HashSet<>();
        flags.add(FAIL_OK);
        flags.add(FAIL_CANTILEVER);
        flags.add(FAIL_CRUSHING);
        flags.add(FAIL_NO_SUPPORT);
        flags.add(FAIL_TENSION);
        assertEquals(5, flags.size(), "All failure flags should be unique");
    }

    @Test
    @DisplayName("Stress sync interval > 0 且 ≤ 100 tick")
    void testStressSyncInterval() {
        assertTrue(STRESS_SYNC_INTERVAL > 0 && STRESS_SYNC_INTERVAL <= 100,
                "STRESS_SYNC_INTERVAL should be reasonable: " + STRESS_SYNC_INTERVAL);
    }

    @Test
    @DisplayName("迭代步數建議：MINOR < MAJOR < COLLAPSE")
    void testIterationSteps() {
        assertTrue(STEPS_MINOR > 0);
        assertTrue(STEPS_MAJOR > STEPS_MINOR);
        assertTrue(STEPS_COLLAPSE > STEPS_MAJOR);
    }

    @Test
    @DisplayName("MG_INTERVAL > 0 且 ≤ STEPS_MINOR")
    void testMGInterval() {
        assertTrue(MG_INTERVAL > 0);
        assertTrue(MG_INTERVAL <= STEPS_MINOR,
                "V-Cycle interval should trigger at least once in minor steps");
    }
}
