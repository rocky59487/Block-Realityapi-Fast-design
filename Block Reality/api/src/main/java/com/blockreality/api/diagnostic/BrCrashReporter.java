package com.blockreality.api.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Block Reality 頂層崩潰報告器。
 *
 * <p>整合 {@link BrLogCapture}、{@link BrCrashAnalyzer}、{@link BrDiagnosticUtil}，
 * 在崩潰或手動觸發時將完整診斷資訊寫入 {@code crashreporter/} 資料夾。
 *
 * <p>使用方式：
 * <pre>
 *   // 模組初始化時（最優先）
 *   BrCrashReporter.install();
 *
 *   // 指令手動觸發（不崩潰）
 *   BrCrashReporter.generateManualReport("reason", null);
 * </pre>
 */
public final class BrCrashReporter {

    private BrCrashReporter() {}

    private static final Logger LOGGER = LogManager.getLogger("BR-CrashReporter");
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String REPORT_DIR = "crashreporter";

    /** 已安裝標記，防止重複安裝。 */
    private static volatile boolean installed = false;

    // ─────────────────────────────────────────────────────────────────
    //  安裝
    // ─────────────────────────────────────────────────────────────────

    /**
     * 安裝為 JVM 預設的 {@link Thread.UncaughtExceptionHandler}。
     *
     * <p>若已有現有的 Handler，會在報告寫入完成後繼續呼叫原始 Handler，
     * 確保 Minecraft/Forge 的崩潰流程不受干擾。
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String reason = "Uncaught exception in thread \"" + thread.getName() + "\"";
                writeReport(reason, throwable);
            } catch (Throwable ignored) {
                // 崩潰報告本身不能再拋出例外
            } finally {
                // 呼叫原始 Handler（Forge/Minecraft 崩潰處理）
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });

        LOGGER.info("[BrCrashReporter] UncaughtExceptionHandler 已安裝");
    }

    // ─────────────────────────────────────────────────────────────────
    //  手動報告
    // ─────────────────────────────────────────────────────────────────

    /**
     * 生成即時診斷報告（不崩潰），適合由指令觸發。
     *
     * @param reason    報告觸發原因說明
     * @param throwable 可選的例外（手動觸發時通常為 null）
     */
    public static void generateManualReport(String reason, Throwable throwable) {
        try {
            writeReport(reason, throwable);
        } catch (Throwable e) {
            LOGGER.error("[BrCrashReporter] 無法生成診斷報告: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  核心：寫入報告
    // ─────────────────────────────────────────────────────────────────

    private static void writeReport(String reason, Throwable throwable) {
        String timestamp = LocalDateTime.now().format(FILE_FMT);
        Path dir = Paths.get(REPORT_DIR);
        Path file = dir.resolve("br-crash-" + timestamp + ".txt");

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[BrCrashReporter] 無法建立報告目錄 {}: {}", dir, e.getMessage());
            return;
        }

        // ── 收集診斷資料（每項獨立 catch，確保部分失敗不影響其他區塊）──
        List<BrLogCapture.LogEntry> recentLog = safeGet(
                BrLogCapture::getRecentEntries, List.of(), "BrLogCapture.getRecentEntries");

        BrCrashAnalyzer.AnalysisResult analysis = safeGet(
                () -> BrCrashAnalyzer.analyze(throwable, recentLog),
                null, "BrCrashAnalyzer.analyze");

        Map<String, String> sysInfo = safeGet(
                BrDiagnosticUtil::collectSystemInfo, Map.of(), "BrDiagnosticUtil.collectSystemInfo");

        Map<String, String> vkInfo = safeGet(
                BrDiagnosticUtil::collectVulkanInfo, Map.of(), "BrDiagnosticUtil.collectVulkanInfo");

        List<BrDiagnosticUtil.ModEntry> modList = safeGet(
                BrDiagnosticUtil::collectModList, List.of(), "BrDiagnosticUtil.collectModList");

        // ── 寫入檔案 ─────────────────────────────────────────────────
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            writeLine(w, "═══════════════════════════════════════════════════════════════");
            writeLine(w, "  Block Reality — Crash / Diagnostic Report");
            writeLine(w, "  Generated: " + timestamp);
            writeLine(w, "═══════════════════════════════════════════════════════════════");
            writeLine(w, "");

            // ── 1. 觸發原因 ──────────────────────────────────────────
            writeLine(w, "── Trigger Reason ──────────────────────────────────────────");
            writeLine(w, reason != null ? reason : "(no reason provided)");
            writeLine(w, "");

            // ── 2. 根因分析 ──────────────────────────────────────────
            writeLine(w, "── Root Cause Analysis ─────────────────────────────────────");
            if (analysis != null) {
                writeLine(w, "Severity : " + BrCrashAnalyzer.severityIcon(analysis.severity()));
                writeLine(w, "Summary  : " + analysis.shortSummary());
                writeLine(w, "BR Related: " + (analysis.isBrRelated() ? "Yes" : "Possibly not"));
                writeLine(w, "");
                writeLine(w, "Possible Causes:");
                for (String cause : analysis.possibleCauses()) {
                    writeLine(w, "  • " + cause);
                }
                writeLine(w, "");
                writeLine(w, "Fix Suggestions:");
                for (String fix : analysis.fixSuggestions()) {
                    writeLine(w, "  → " + fix);
                }
            } else {
                writeLine(w, "(analysis unavailable)");
            }
            writeLine(w, "");

            // ── 3. 完整 Stack Trace ──────────────────────────────────
            writeLine(w, "── Full Stack Trace ────────────────────────────────────────");
            if (throwable != null) {
                writeThrowable(w, throwable, 0);
            } else {
                writeLine(w, "(no exception — manual report)");
            }
            writeLine(w, "");

            // ── 4. 系統資訊 ──────────────────────────────────────────
            writeLine(w, "── System Info ─────────────────────────────────────────────");
            for (Map.Entry<String, String> e : sysInfo.entrySet()) {
                writeLine(w, String.format("  %-24s %s", e.getKey() + ":", e.getValue()));
            }
            writeLine(w, "");

            // ── 5. Vulkan / PFSF 狀態 ────────────────────────────────
            writeLine(w, "── Vulkan / PFSF Status ────────────────────────────────────");
            for (Map.Entry<String, String> e : vkInfo.entrySet()) {
                writeLine(w, String.format("  %-28s %s", e.getKey() + ":", e.getValue()));
            }
            writeLine(w, "");

            // ── 6. 最近日誌（WARN 以上）─────────────────────────────
            writeLine(w, "── Recent Log (WARN+, up to " + BrLogCapture.RING_SIZE + " entries) ──────────────");
            if (recentLog.isEmpty()) {
                writeLine(w, "  (no entries captured — BrLogCapture may not be installed)");
            } else {
                for (BrLogCapture.LogEntry entry : recentLog) {
                    writeLine(w, String.format("  [%s] %-5s %-30s %s",
                            entry.timestamp(), entry.level(),
                            shortenLogger(entry.logger()), entry.message()));
                }
            }
            writeLine(w, "");

            // ── 7. 已載入的 Forge 模組 ──────────────────────────────
            writeLine(w, "── Loaded Mods ─────────────────────────────────────────────");
            for (BrDiagnosticUtil.ModEntry mod : modList) {
                String marker = mod.isBlockReality() ? " ★" : "";
                writeLine(w, String.format("  %-40s %s%s",
                        mod.modId(), mod.version(), marker));
            }
            writeLine(w, "");

            writeLine(w, "═══════════════════════════════════════════════════════════════");
            writeLine(w, "  End of Report — " + file.getFileName());
            writeLine(w, "═══════════════════════════════════════════════════════════════");

        } catch (IOException e) {
            LOGGER.error("[BrCrashReporter] 無法寫入報告至 {}: {}", file, e.getMessage());
            return;
        }

        LOGGER.info("[BrCrashReporter] 崩潰報告已寫入: {}", file.toAbsolutePath());
    }

    // ─────────────────────────────────────────────────────────────────
    //  工具方法
    // ─────────────────────────────────────────────────────────────────

    /** 遞迴寫入 Throwable 及其 cause chain。 */
    private static void writeThrowable(BufferedWriter w, Throwable t, int depth) throws IOException {
        if (depth > 8) {
            writeLine(w, "  [cause chain truncated at depth " + depth + "]");
            return;
        }
        String prefix = depth == 0 ? "" : "Caused by: ";
        writeLine(w, prefix + t.getClass().getName() + ": " + t.getMessage());
        for (StackTraceElement frame : t.getStackTrace()) {
            writeLine(w, "\tat " + frame);
        }
        if (t.getCause() != null) {
            writeThrowable(w, t.getCause(), depth + 1);
        }
    }

    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.newLine();
    }

    /** 縮短 logger 名稱至最後 3 節。 */
    private static String shortenLogger(String name) {
        if (name == null) return "?";
        String[] parts = name.split("\\.");
        if (parts.length <= 3) return name;
        return parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /** 安全呼叫 supplier，失敗時回傳 fallback 並記錄警告。 */
    private static <T> T safeGet(java.util.concurrent.Callable<T> supplier, T fallback, String label) {
        try {
            return supplier.call();
        } catch (Throwable e) {
            LOGGER.warn("[BrCrashReporter] {} 失敗: {}", label, e.getMessage());
            return fallback;
        }
    }
}
