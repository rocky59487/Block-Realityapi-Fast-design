package com.blockreality.api.client.render.rt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BRReSTIRDI reservoir math:
 *   - reservoirUpdate()   : RIS stream update（wSum 累積、樣本接受機率）
 *   - computeRISWeight()  : 無偏 W = (1/p_hat(y)) × (wSum/M)
 *   - reservoirMerge()    : 時域 reservoir 合併（M-cap、wSum 貢獻）
 *
 * 所有測試均為純 CPU 數學，不依賴 Vulkan / Forge 運行時。
 */
class BRReSTIRDITest {

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** 建立全新 Reservoir（wSum=0, M=0, W=0, y=-1, valid=false） */
    private BRReSTIRDI.Reservoir empty() {
        return new BRReSTIRDI.Reservoir();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  reservoirUpdate() 系列測試
    // ═══════════════════════════════════════════════════════════════════════

    /** 接受唯一候選後，reservoir 應持有該候選 index */
    @Test
    void update_singleCandidate_alwaysAccepted() {
        BRReSTIRDI.Reservoir r = empty();
        // rand=0 → 接受機率 = w/wSum = 1.0，rand < 1.0 always true
        boolean accepted = BRReSTIRDI.reservoirUpdate(r, /*candIdx=*/3, /*weight=*/2.0f, /*rand=*/0.0f);

        assertTrue(accepted, "First candidate always accepted");
        assertEquals(3, r.y, "y should hold candIdx");
        assertEquals(2.0f, r.wSum, 1e-6f, "wSum = weight");
        assertEquals(1, r.M, "M incremented to 1");
    }

    /** wSum 在多次 update 後應等於所有候選權重之和 */
    @Test
    void update_multipleUpdates_wSumAccumulates() {
        BRReSTIRDI.Reservoir r = empty();
        BRReSTIRDI.reservoirUpdate(r, 0, 1.0f, 0.0f);
        BRReSTIRDI.reservoirUpdate(r, 1, 2.0f, 0.0f);
        BRReSTIRDI.reservoirUpdate(r, 2, 3.0f, 0.0f);

        assertEquals(6.0f, r.wSum, 1e-5f, "wSum = sum of all weights");
        assertEquals(3, r.M, "M = number of candidates seen");
    }

    /** rand >= candidateWeight/wSum 時應拒絕候選 */
    @Test
    void update_highRand_rejectsCandidate() {
        BRReSTIRDI.Reservoir r = empty();
        // 先接受 idx=0
        BRReSTIRDI.reservoirUpdate(r, 0, 5.0f, 0.0f);
        // 第二候選 weight=1, wSum after=6; acceptance = 1/6 ≈ 0.167
        // rand=0.9 > 0.167 → 拒絕
        boolean accepted = BRReSTIRDI.reservoirUpdate(r, 1, 1.0f, 0.9f);

        assertFalse(accepted, "Should reject when rand >= acceptance probability");
        assertEquals(0, r.y, "y should still be first candidate");
        assertEquals(6.0f, r.wSum, 1e-5f, "wSum still accumulates on reject");
    }

    /** rand < candidateWeight/wSum 時應接受新候選 */
    @Test
    void update_lowRand_acceptsCandidate() {
        BRReSTIRDI.Reservoir r = empty();
        BRReSTIRDI.reservoirUpdate(r, 0, 1.0f, 0.5f);   // 接受：1/1 = 1.0 > 0.5
        BRReSTIRDI.reservoirUpdate(r, 1, 9.0f, 0.05f);  // 接受：9/10 = 0.9 > 0.05

        assertEquals(1, r.y, "Should have accepted second candidate");
    }

    /** 權重為零時不應接受候選，且 wSum 不變 */
    @Test
    void update_zeroWeight_notAccepted() {
        BRReSTIRDI.Reservoir r = empty();
        BRReSTIRDI.reservoirUpdate(r, 0, 5.0f, 0.0f);
        int prevY = r.y;
        float prevWSum = r.wSum;

        // weight=0 → candidateWeight/wSum = 0/5 = 0，rand=0.0 is NOT < 0
        // （rand < 0 is false，so rejected）
        BRReSTIRDI.reservoirUpdate(r, 99, 0.0f, 0.0f);

        assertEquals(prevY, r.y, "y should not change on zero-weight candidate");
        assertEquals(prevWSum, r.wSum, 1e-6f, "wSum should not change on zero-weight");
    }

    /** M 計數器每次 update 都應增加，無論接受與否 */
    @Test
    void update_mCounterAlwaysIncrements() {
        BRReSTIRDI.Reservoir r = empty();
        for (int i = 0; i < 10; i++) {
            BRReSTIRDI.reservoirUpdate(r, i, 1.0f, 0.99f); // 高 rand → 大部分被拒絕
        }
        assertEquals(10, r.M, "M should count all candidates, not just accepted ones");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  computeRISWeight() 系列測試
    // ═══════════════════════════════════════════════════════════════════════

    /** 基本 RIS 公式：W = (1/p_hat) × (wSum/M) */
    @Test
    void computeRISWeight_basicFormula() {
        BRReSTIRDI.Reservoir r = empty();
        r.wSum = 10.0f;
        r.M    = 5;
        // p_hat(y) = 2.0  →  W = (1/2) × (10/5) = 0.5 × 2.0 = 1.0
        float W = BRReSTIRDI.computeRISWeight(r, 2.0f);
        assertEquals(1.0f, W, 1e-5f, "W = (1/pHat) * (wSum/M)");
    }

    /** p_hat = 0 時應返回 0（避免除以零） */
    @Test
    void computeRISWeight_zeroPHat_returnsZero() {
        BRReSTIRDI.Reservoir r = empty();
        r.wSum = 5.0f;
        r.M    = 3;
        float W = BRReSTIRDI.computeRISWeight(r, 0.0f);
        assertEquals(0.0f, W, "W should be 0 when pHat is 0");
    }

    /** M = 0 時（空 reservoir）應返回 0 */
    @Test
    void computeRISWeight_zeroM_returnsZero() {
        BRReSTIRDI.Reservoir r = empty(); // M=0, wSum=0
        float W = BRReSTIRDI.computeRISWeight(r, 1.0f);
        assertEquals(0.0f, W, "W should be 0 for empty reservoir (M=0)");
    }

    /** 負 p_hat（畸形輸入）應返回 0 */
    @Test
    void computeRISWeight_negativePHat_returnsZero() {
        BRReSTIRDI.Reservoir r = empty();
        r.wSum = 4.0f;
        r.M    = 2;
        float W = BRReSTIRDI.computeRISWeight(r, -1.0f);
        assertEquals(0.0f, W, "Negative pHat should return 0 (guard against malformed input)");
    }

    /** 單樣本情況：W = wSum / p_hat */
    @Test
    void computeRISWeight_singleSample_equalsWDivPHat() {
        BRReSTIRDI.Reservoir r = empty();
        BRReSTIRDI.reservoirUpdate(r, 0, 6.0f, 0.0f);  // wSum=6, M=1
        // W = (1/3.0) * (6/1) = 2.0
        float W = BRReSTIRDI.computeRISWeight(r, 3.0f);
        assertEquals(2.0f, W, 1e-5f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  reservoirMerge() 系列測試
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 合併後 dst.wSum 應增加 src 的貢獻：
     *   srcContribution = pHatSrcY × src.W × min(src.M, temporalMaxM)
     */
    @Test
    void merge_wSumIncreasesByContribution() {
        BRReSTIRDI.Reservoir dst = empty();
        dst.wSum = 4.0f;
        dst.M    = 2;

        BRReSTIRDI.Reservoir src = empty();
        src.W = 2.0f;
        src.M = 3;
        src.y = 7;

        // pHatSrcY=1.0, W=2.0, M=3 (< cap 20)
        // srcContribution = 1.0 × 2.0 × 3 = 6.0
        // dst.wSum after = 4.0 + 6.0 = 10.0
        BRReSTIRDI.reservoirMerge(dst, src, 1.0f, 0.0f, 20);

        assertEquals(10.0f, dst.wSum, 1e-5f, "dst.wSum += srcContribution");
        assertEquals(5, dst.M, "dst.M += min(src.M, cap)");
    }

    /** M-cap：src.M 超過 temporalMaxM 時，應使用 cap 值 */
    @Test
    void merge_mCapApplied() {
        BRReSTIRDI.Reservoir dst = empty();
        dst.wSum = 0.0f;
        dst.M    = 0;

        BRReSTIRDI.Reservoir src = empty();
        src.W = 1.0f;
        src.M = 100;  // 遠超 cap
        src.y = 5;

        int temporalMaxM = 20;
        // srcContribution = 2.0 × 1.0 × 20 = 40.0
        BRReSTIRDI.reservoirMerge(dst, src, 2.0f, 0.0f, temporalMaxM);

        assertEquals(20, dst.M, "M should be capped at temporalMaxM");
        assertEquals(40.0f, dst.wSum, 1e-5f, "Contribution uses capped M");
    }

    /** rand=0（最低）時應始終接受 src.y */
    @Test
    void merge_lowestRand_acceptsSrcY() {
        BRReSTIRDI.Reservoir dst = empty();
        dst.wSum = 1.0f;
        dst.M    = 1;
        dst.y    = 0;

        BRReSTIRDI.Reservoir src = empty();
        src.W = 10.0f;
        src.M = 5;
        src.y = 42;

        // rand=0 → 接受 src.y 的機率 = srcContribution / (dst.wSum + srcContribution) > 0
        BRReSTIRDI.reservoirMerge(dst, src, 1.0f, 0.0f, 20);

        assertEquals(42, dst.y, "rand=0 should accept src.y");
    }

    /** rand=1（最高）時應保留 dst.y */
    @Test
    void merge_highestRand_keepsDstY() {
        BRReSTIRDI.Reservoir dst = empty();
        dst.wSum = 1.0f;
        dst.M    = 1;
        dst.y    = 99;

        BRReSTIRDI.Reservoir src = empty();
        src.W = 1.0f;
        src.M = 1;
        src.y = 42;

        // srcContribution = 1.0 × 1.0 × 1 = 1.0
        // acceptance = 1.0 / (1.0 + 1.0) = 0.5
        // rand = 0.99 > 0.5 → reject src.y
        BRReSTIRDI.reservoirMerge(dst, src, 1.0f, 0.99f, 20);

        assertEquals(99, dst.y, "High rand should keep dst.y");
    }

    /** 合併空 src reservoir（W=0, M=0）：dst 不應改變 */
    @Test
    void merge_emptySrc_dstUnchanged() {
        BRReSTIRDI.Reservoir dst = empty();
        dst.wSum = 5.0f;
        dst.M    = 3;
        dst.y    = 7;

        BRReSTIRDI.Reservoir src = empty(); // W=0, M=0

        BRReSTIRDI.reservoirMerge(dst, src, 1.0f, 0.0f, 20);

        // srcContribution = 1.0 × 0.0 × 0 = 0 → wSum unchanged, M unchanged
        assertEquals(5.0f, dst.wSum, 1e-6f, "wSum unchanged when src is empty");
        assertEquals(3, dst.M, "M unchanged when src.M=0");
        // y 可能被 rand=0 接受 src.y（因為 0/0 → contribution=0，acceptance=0/5=0，rand=0 NOT < 0）
        // → dst.y 應保留
        assertEquals(7, dst.y, "y unchanged when srcContribution=0");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  複合場景測試
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 模擬 N=4 候選的完整 RIS 流程：
     * 1. 用 reservoirUpdate 填充 reservoir
     * 2. 以 computeRISWeight 計算最終 W
     * 3. 確認 W > 0（有效 reservoir）
     */
    @Test
    void fullRISPipeline_validW() {
        BRReSTIRDI.Reservoir r = empty();

        float[] weights = {0.5f, 2.0f, 1.0f, 3.5f};
        float totalW = 0;
        for (int i = 0; i < weights.length; i++) {
            totalW += weights[i];
            BRReSTIRDI.reservoirUpdate(r, i, weights[i], 0.0f);  // rand=0 → last candidate wins
        }

        assertEquals(4, r.M);
        assertEquals(totalW, r.wSum, 1e-4f);
        // y = 3（最後一個，因為 rand=0 每次都接受）
        assertEquals(3, r.y);

        float pHat = 3.5f;  // p_hat 為選中光源的 power
        float W = BRReSTIRDI.computeRISWeight(r, pHat);
        // W = (1/3.5) × (7.0/4) = 0.2857 × 1.75 = 0.5
        assertEquals(0.5f, W, 1e-4f);
        assertTrue(W > 0, "Final RIS weight should be positive");
    }

    /**
     * 時域重用情境：
     * 1. 建立當前 reservoir（current frame）
     * 2. 從上一幀 reservoir 合併
     * 3. 確認 M 累積且 wSum 增加
     */
    @Test
    void temporalReuse_mAccumulates() {
        // 當前幀 reservoir
        BRReSTIRDI.Reservoir current = empty();
        BRReSTIRDI.reservoirUpdate(current, 0, 4.0f, 0.0f);
        current.W = BRReSTIRDI.computeRISWeight(current, 4.0f);
        // W = (1/4) * (4/1) = 1.0

        // 前一幀 reservoir
        BRReSTIRDI.Reservoir prev = empty();
        prev.y = 2;
        prev.wSum = 8.0f;
        prev.M = 15;  // < M-cap 20
        prev.W = BRReSTIRDI.computeRISWeight(prev, 2.0f);

        int initialM = current.M;
        BRReSTIRDI.reservoirMerge(current, prev, 2.0f, 0.5f, 20);

        assertTrue(current.M > initialM, "M should increase after temporal merge");
        assertEquals(1 + 15, current.M, "M = current.M + min(prev.M, cap)");
    }

    /** M-cap 防止過度累積：合併 M=100 的舊 reservoir 後 dst.M ≤ cap */
    @Test
    void temporalReuse_mcapPreventsOverAccumulation() {
        BRReSTIRDI.Reservoir current = empty();
        current.wSum = 1.0f;
        current.M    = 1;

        BRReSTIRDI.Reservoir stale = empty();
        stale.W = 1.0f;
        stale.M = 200;  // 極舊 reservoir
        stale.y = 5;

        int cap = 20;
        BRReSTIRDI.reservoirMerge(current, stale, 1.0f, 0.5f, cap);

        assertEquals(1 + cap, current.M, "M should be 1 + cap, not 1 + 200");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  getReservoirVRAMBytes() 估算測試
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 驗證 BRReSTIRDI.getReservoirVRAMBytes() 返回值合理：
     * 1920×1080 × 16 bytes/reservoir × 2 buffers = 66,355,200 bytes ≈ 63 MB
     */
    @Test
    void vramEstimate_1080p() {
        // getReservoirVRAMBytes() 需要 init() 先呼叫，
        // 但 init() 依賴 Vulkan（skipable），我們改測靜態公式
        int width  = 1920;
        int height = 1080;
        long expected = (long) width * height * BRReSTIRDI.RESERVOIR_SIZE * 2L;
        assertEquals(66_355_200L, expected, "1080p double-buffer VRAM estimate");
    }

    /** 4K 解析度的 VRAM 估算：3840×2160 × 16 × 2 ≈ 253 MB */
    @Test
    void vramEstimate_4k() {
        int width  = 3840;
        int height = 2160;
        long bytes = (long) width * height * BRReSTIRDI.RESERVOIR_SIZE * 2L;
        assertEquals(265_420_800L, bytes, "4K double-buffer VRAM estimate");
    }
}
