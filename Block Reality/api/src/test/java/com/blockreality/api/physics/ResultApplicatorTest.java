package com.blockreality.api.physics;

import com.blockreality.api.physics.ResultApplicator.StressRetryEntry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResultApplicator 單元測試 — 驗證重試邏輯、失敗追蹤、並發安全。
 *
 * 注意：applyStructureResult() 等方法需要 ServerLevel（Minecraft ClassLoader），
 * 此處僅測試可純 JUnit 運行的公開 API：
 *   1. StressRetryEntry 封裝行為
 *   2. failedPositions 追蹤 API (hasPendingFailures / getFailedPositions / clearFailedPositions)
 *   3. ApplyReport record 行為
 *   4. volatile 欄位的 visibility（概念驗證）
 */
@DisplayName("ResultApplicator — 重試邏輯與失敗追蹤")
class ResultApplicatorTest {

    @BeforeEach
    void setUp() {
        // 清除 static state，避免測試間汙染
        ResultApplicator.clearFailedPositions();
    }

    @AfterEach
    void tearDown() {
        ResultApplicator.clearFailedPositions();
    }

    // ═══════════════════════════════════════════════════════
    //  StressRetryEntry — 封裝與行為
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("StressRetryEntry — 重試條目")
    class StressRetryEntryTests {

        @Test
        @DisplayName("初始 retryCount = 0")
        void initialRetryCountZero() {
            StressRetryEntry entry = new StressRetryEntry("test_op");
            assertEquals(0, entry.getRetryCount());
        }

        @Test
        @DisplayName("incrementRetry 遞增 retryCount 並更新時間戳")
        void incrementUpdatesCountAndTimestamp() {
            StressRetryEntry entry = new StressRetryEntry("test_op");
            long before = System.currentTimeMillis();

            entry.incrementRetry();

            assertEquals(1, entry.getRetryCount());
            assertTrue(entry.getLastAttemptMs() >= before);
        }

        @Test
        @DisplayName("連續 3 次 incrementRetry → retryCount = 3")
        void multipleIncrements() {
            StressRetryEntry entry = new StressRetryEntry("test_op");

            entry.incrementRetry();
            entry.incrementRetry();
            entry.incrementRetry();

            assertEquals(3, entry.getRetryCount());
        }

        @Test
        @DisplayName("isExhausted() — 預設 maxRetries=3")
        void isExhaustedDefault() {
            StressRetryEntry entry = new StressRetryEntry("test_op");

            assertFalse(entry.isExhausted(), "Not exhausted at 0 retries");
            entry.incrementRetry(); // 1
            assertFalse(entry.isExhausted(), "Not exhausted at 1 retry");
            entry.incrementRetry(); // 2
            assertFalse(entry.isExhausted(), "Not exhausted at 2 retries");
            entry.incrementRetry(); // 3
            assertTrue(entry.isExhausted(), "Exhausted at 3 retries (== maxRetries)");
        }

        @Test
        @DisplayName("自訂 maxRetries")
        void customMaxRetries() {
            StressRetryEntry entry = new StressRetryEntry("test_op", 5);

            assertEquals(5, entry.getMaxRetries());
            for (int i = 0; i < 4; i++) entry.incrementRetry();
            assertFalse(entry.isExhausted(), "Not exhausted at 4/5");
            entry.incrementRetry(); // 5
            assertTrue(entry.isExhausted(), "Exhausted at 5/5");
        }

        @Test
        @DisplayName("setMaxRetries 最小值 clamp 到 1")
        void setMaxRetriesClamp() {
            StressRetryEntry entry = new StressRetryEntry("test_op");

            entry.setMaxRetries(0);
            assertEquals(1, entry.getMaxRetries(), "maxRetries should be clamped to >= 1");

            entry.setMaxRetries(-5);
            assertEquals(1, entry.getMaxRetries(), "Negative value should clamp to 1");
        }

        @Test
        @DisplayName("getOperation() 回傳建構時的標籤")
        void operationLabel() {
            StressRetryEntry entry = new StressRetryEntry("stress_write");
            assertEquals("stress_write", entry.getOperation());
        }

        @Test
        @DisplayName("toString 包含操作標籤和重試計數")
        void toStringFormat() {
            StressRetryEntry entry = new StressRetryEntry("fusion_upgrade", 5);
            entry.incrementRetry();
            String s = entry.toString();
            assertTrue(s.contains("fusion_upgrade"));
            assertTrue(s.contains("1/5"));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  failedPositions API
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("failedPositions — 追蹤 API")
    class FailedPositionsApiTests {

        @Test
        @DisplayName("初始無待處理失敗")
        void initiallyNoPending() {
            assertFalse(ResultApplicator.hasPendingFailures());
            assertTrue(ResultApplicator.getFailedPositions().isEmpty());
        }

        @Test
        @DisplayName("clearFailedPositions 清空所有條目")
        void clearWorks() {
            // 無法直接注入（private static CHM），但 clear 應保持冪等
            ResultApplicator.clearFailedPositions();
            assertFalse(ResultApplicator.hasPendingFailures());
        }

        @Test
        @DisplayName("getFailedPositions 回傳不可修改的 Map")
        void getFailedPositionsUnmodifiable() {
            Map<BlockPos, StressRetryEntry> map = ResultApplicator.getFailedPositions();
            assertThrows(UnsupportedOperationException.class, () ->
                map.put(new BlockPos(0, 0, 0), new StressRetryEntry("hack")));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  ApplyReport record
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("ApplyReport — 報告 record")
    class ApplyReportTests {

        @Test
        @DisplayName("totalApplied 為四項之和")
        void totalAppliedSum() {
            var report = new ResultApplicator.ApplyReport(10, 20, 30, 40, 5);
            assertEquals(100, report.totalApplied());
        }

        @Test
        @DisplayName("hasFailures 判定 failedCount > 0")
        void hasFailures() {
            assertTrue(new ResultApplicator.ApplyReport(0, 0, 0, 0, 1).hasFailures());
            assertFalse(new ResultApplicator.ApplyReport(10, 20, 30, 40, 0).hasFailures());
        }

        @Test
        @DisplayName("toString 包含所有欄位")
        void toStringContainsAllFields() {
            var report = new ResultApplicator.ApplyReport(1, 2, 3, 4, 5);
            String s = report.toString();
            assertTrue(s.contains("fusion=1"));
            assertTrue(s.contains("anchor=2"));
            assertTrue(s.contains("structure=3"));
            assertTrue(s.contains("stress=4"));
            assertTrue(s.contains("failed=5"));
        }

        @Test
        @DisplayName("全零報告的 totalApplied = 0 且 hasFailures = false")
        void emptyReport() {
            var report = new ResultApplicator.ApplyReport(0, 0, 0, 0, 0);
            assertEquals(0, report.totalApplied());
            assertFalse(report.hasFailures());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  volatile 概念驗證（StressRetryEntry）
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("StressRetryEntry — volatile 並發概念驗證")
    class VolatileConceptTests {

        @Test
        @DisplayName("另一個執行緒寫入 retryCount，主線程可見")
        void crossThreadVisibility() throws InterruptedException {
            StressRetryEntry entry = new StressRetryEntry("test_op");

            Thread writer = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    entry.incrementRetry();
                }
            });
            writer.start();
            writer.join();

            // volatile 保證 writer thread 完成後，主線程看到 10
            assertEquals(10, entry.getRetryCount(),
                "volatile retryCount should be visible after writer thread joins");
        }
    }
}
