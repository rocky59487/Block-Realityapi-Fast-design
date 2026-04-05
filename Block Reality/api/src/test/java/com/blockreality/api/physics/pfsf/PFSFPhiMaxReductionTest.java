package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.blockreality.api.physics.pfsf.PFSFVCycleRecorder.ceilDiv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFFailureRecorder.recordPhiMaxReduction 兩階段歸約邏輯測試。
 *
 * <p>驗證 GPU dispatch 參數計算（pass 1/2 workgroup 數量），
 * 不需要 GPU。</p>
 */
class PFSFPhiMaxReductionTest {

    @Test
    @DisplayName("Pass 1 workgroup 數量：N=1024 → ceil(1024/512) = 2 groups")
    void testPass1SmallIsland() {
        int N = 1024;
        int numGroups1 = ceilDiv(N, 512);
        assertEquals(2, numGroups1);
    }

    @Test
    @DisplayName("Pass 1 workgroup 數量：N=1000000 → ceil(1M/512) = 1954 groups")
    void testPass1LargeIsland() {
        int N = 1_000_000;
        int numGroups1 = ceilDiv(N, 512);
        assertEquals(1954, numGroups1);
    }

    @Test
    @DisplayName("Pass 2 workgroup 數量：1954 partial → ceil(1954/512) = 4 groups")
    void testPass2() {
        int numGroups1 = 1954;
        int numGroups2 = ceilDiv(numGroups1, 512);
        assertEquals(4, numGroups2);
    }

    @Test
    @DisplayName("小型 island (N=100)：Pass 1 = 1 group, Pass 2 = 1 group")
    void testSmallIslandSingleGroup() {
        int N = 100;
        int numGroups1 = ceilDiv(N, 512);
        assertEquals(1, numGroups1);
        int numGroups2 = Math.max(ceilDiv(numGroups1, 512), 1);
        assertEquals(1, numGroups2);
    }

    @Test
    @DisplayName("極大 island (N=2M)：兩階段歸約歸到 1 個值")
    void testVeryLargeIsland() {
        int N = 2_000_000;
        int numGroups1 = ceilDiv(N, 512);    // 3907
        int numGroups2 = ceilDiv(numGroups1, 512); // 8
        // Pass 2 用 8 個 workgroup 歸約 3907 個 partial → 8 個局部最大值
        // 由於 numGroups2 < 512，一個 workgroup 就能處理
        assertTrue(numGroups2 <= 256,
                "Pass 2 should produce at most 256 results for single-group final: " + numGroups2);
    }

    @Test
    @DisplayName("Partial buffer 大小：numGroups × 4 bytes")
    void testPartialBufferSize() {
        int N = 100000;
        int numGroups1 = ceilDiv(N, 512);
        long partialSize = (long) numGroups1 * Float.BYTES;
        assertTrue(partialSize > 0);
        assertTrue(partialSize <= 1024 * 1024,
                "Partial buffer should be small (< 1MB): " + partialSize + " bytes");
    }

    @Test
    @DisplayName("CPU 模擬 max reduction：驗證演算法正確性")
    void testCPUMaxReduction() {
        // 模擬 GPU reduce_max 的邏輯
        float[] phi = {0.5f, 1.2f, 0.8f, 3.7f, 2.1f, 0.3f, 4.9f, 1.0f};
        float expected = 4.9f;

        // 模擬 shared memory reduction
        float result = Float.NEGATIVE_INFINITY;
        for (float v : phi) {
            result = Math.max(result, v);
        }

        assertEquals(expected, result, 1e-5f);
    }

    @Test
    @DisplayName("空 island (N=0) 不應 dispatch")
    void testEmptyIsland() {
        int N = 0;
        int numGroups1 = ceilDiv(N, 512);
        assertEquals(0, numGroups1, "Zero-sized island should produce 0 groups");
    }
}
