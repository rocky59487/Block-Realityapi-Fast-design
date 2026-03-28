package com.blockreality.api.client.render.test;

import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Block Reality Phase 13 整合測試統一入口。
 *
 * 整合所有驗證器：
 *   1. BRPipelineValidator — 子系統接入 + FBO + Shader + Config + GL state
 *   2. BRStressTest — 壓力測試（FBO ping-pong、shader 循環、天氣狀態機...）
 *   3. BRMemoryLeakScanner — GL 資源洩漏掃描
 *   4. BRFastDesignValidator — Fast Design SPI 接入驗證
 *
 * 調用方式：
 *   - 遊戲內指令：/br test all
 *   - 開發環境：BRIntegrationTestRunner.runAll()
 *   - 單項測試：BRIntegrationTestRunner.runValidation() / runStress() / runMemory() / runFastDesign()
 *
 * 所有測試在 GL context 主執行緒執行，不另開執行緒。
 */
@OnlyIn(Dist.CLIENT)
public final class BRIntegrationTestRunner {
    private BRIntegrationTestRunner() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-IntegrationTest");

    /** 全部測試結果摘要 */
    public static final class TestSummary {
        public final int totalTests;
        public final int passed;
        public final int failed;
        public final long totalDurationMs;
        public final boolean allPassed;

        public TestSummary(int totalTests, int passed, int failed, long totalDurationMs) {
            this.totalTests = totalTests;
            this.passed = passed;
            this.failed = failed;
            this.totalDurationMs = totalDurationMs;
            this.allPassed = (failed == 0);
        }

        @Override
        public String toString() {
            return String.format("═══ 整合測試摘要: %d/%d 通過 (%d 失敗) — %dms ═══",
                passed, totalTests, failed, totalDurationMs);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  全部執行
    // ═══════════════════════════════════════════════════════════════

    /**
     * 執行所有 Phase 13 整合測試。
     * 必須在 GL context 主執行緒、管線 init() 之後呼叫。
     *
     * @return 測試摘要
     */
    public static TestSummary runAll() {
        if (!BRRenderPipeline.isInitialized()) {
            LOG.error("管線未初始化，無法執行整合測試");
            return new TestSummary(0, 0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        int totalTests = 0;
        int totalPassed = 0;
        int totalFailed = 0;

        LOG.info("╔══════════════════════════════════════════════════════════╗");
        LOG.info("║  Block Reality Phase 13 — 全管線整合測試               ║");
        LOG.info("║  32 子系統 · 40+ Shader · 15 Pass Composite Chain      ║");
        LOG.info("╚══════════════════════════════════════════════════════════╝");

        // ── 1. Pipeline Validation ──
        LOG.info("");
        LOG.info("▸ [1/4] 管線整合驗證...");
        List<BRPipelineValidator.ValidationResult> validationResults =
            BRPipelineValidator.runFullValidation();
        for (BRPipelineValidator.ValidationResult r : validationResults) {
            totalTests++;
            if (r.passed) totalPassed++; else totalFailed++;
        }

        // ── 2. Stress Test ──
        LOG.info("");
        LOG.info("▸ [2/4] 壓力測試...");
        List<BRStressTest.StressResult> stressResults =
            BRStressTest.runAllTests();
        for (BRStressTest.StressResult r : stressResults) {
            totalTests++;
            if (r.passed) totalPassed++; else totalFailed++;
        }

        // ── 3. Memory Leak Scan ──
        LOG.info("");
        LOG.info("▸ [3/4] 記憶體洩漏掃描...");
        BRMemoryLeakScanner.captureBaseline();
        List<BRMemoryLeakScanner.LeakReport> leakReports =
            BRMemoryLeakScanner.scanForLeaks();
        for (BRMemoryLeakScanner.LeakReport r : leakReports) {
            totalTests++;
            if (!r.isLeak) totalPassed++; else totalFailed++;
        }

        // 已知紋理有效性
        List<BRMemoryLeakScanner.LeakReport> textureReports =
            BRMemoryLeakScanner.validateKnownTextures();
        for (BRMemoryLeakScanner.LeakReport r : textureReports) {
            totalTests++;
            if (!r.isLeak) totalPassed++; else totalFailed++;
        }

        // Resize 洩漏測試
        int screenW = (int) BRFramebufferManager.getScreenWidth();
        int screenH = (int) BRFramebufferManager.getScreenHeight();
        if (screenW > 0 && screenH > 0) {
            List<BRMemoryLeakScanner.LeakReport> resizeReports =
                BRMemoryLeakScanner.testResizeLeak(screenW, screenH);
            for (BRMemoryLeakScanner.LeakReport r : resizeReports) {
                totalTests++;
                if (!r.isLeak) totalPassed++; else totalFailed++;
            }
        }

        // ── 4. Fast Design Validation ──
        LOG.info("");
        LOG.info("▸ [4/4] Fast Design 接入驗證...");
        List<BRFastDesignValidator.FDValidationResult> fdResults =
            BRFastDesignValidator.runFullValidation();
        for (BRFastDesignValidator.FDValidationResult r : fdResults) {
            totalTests++;
            if (r.passed) totalPassed++; else totalFailed++;
        }

        // ── 摘要 ──
        long duration = System.currentTimeMillis() - startTime;
        TestSummary summary = new TestSummary(totalTests, totalPassed, totalFailed, duration);

        LOG.info("");
        LOG.info("╔══════════════════════════════════════════════════════════╗");
        if (summary.allPassed) {
            LOG.info("║  ✓ ALL TESTS PASSED — {} tests in {}ms              ║",
                summary.totalTests, summary.totalDurationMs);
        } else {
            LOG.error("║  ✗ {} TESTS FAILED — {}/{} passed in {}ms          ║",
                summary.failed, summary.passed, summary.totalTests, summary.totalDurationMs);
        }
        LOG.info("╚══════════════════════════════════════════════════════════╝");

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════
    //  個別測試入口
    // ═══════════════════════════════════════════════════════════════

    /** 僅執行管線驗證 */
    public static List<BRPipelineValidator.ValidationResult> runValidation() {
        return BRPipelineValidator.runFullValidation();
    }

    /** 僅執行壓力測試 */
    public static List<BRStressTest.StressResult> runStress() {
        return BRStressTest.runAllTests();
    }

    /** 僅執行記憶體掃描 */
    public static List<BRMemoryLeakScanner.LeakReport> runMemory() {
        BRMemoryLeakScanner.captureBaseline();
        return BRMemoryLeakScanner.scanForLeaks();
    }

    /** 僅執行 Fast Design 驗證 */
    public static List<BRFastDesignValidator.FDValidationResult> runFastDesign() {
        return BRFastDesignValidator.runFullValidation();
    }
}
