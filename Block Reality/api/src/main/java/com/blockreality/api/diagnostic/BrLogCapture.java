package com.blockreality.api.diagnostic;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Log4j 2 Appender — 在記憶體中環形緩衝最後 N 條日誌，
 * 供崩潰報告讀取。
 *
 * <p>使用 ConcurrentLinkedDeque 保證線程安全，並透過
 * 反射動態附加至 Root Logger，不需修改 log4j2.xml。
 */
@Plugin(name = "BrLogCapture", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class BrLogCapture extends AbstractAppender {

    // ── 配置 ────────────────────────────────────────────────────────
    public static final int RING_SIZE = 150;         // 緩衝條數
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── 靜態單例 ─────────────────────────────────────────────────────
    private static volatile BrLogCapture INSTANCE;

    // ── 日誌條目 ─────────────────────────────────────────────────────
    public record LogEntry(String timestamp, String level, String logger, String message, boolean isError) {}

    private final ConcurrentLinkedDeque<LogEntry> buffer = new ConcurrentLinkedDeque<>();

    // ─────────────────────────────────────────────────────────────────
    //  構造 & 工廠
    // ─────────────────────────────────────────────────────────────────

    private BrLogCapture(String name, Filter filter) {
        super(name, filter, PatternLayout.createDefaultLayout(), true, null);
    }

    @PluginFactory
    public static BrLogCapture createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter) {
        return new BrLogCapture(name != null ? name : "BrLogCapture", filter);
    }

    // ─────────────────────────────────────────────────────────────────
    //  安裝 / 卸載
    // ─────────────────────────────────────────────────────────────────

    /**
     * 動態安裝 Appender — 在 BlockRealityMod 構造函數中呼叫一次即可。
     * 使用反射確保不依賴特定 Log4j 版本 API。
     */
    public static synchronized void install() {
        if (INSTANCE != null) return;
        try {
            INSTANCE = new BrLogCapture("BrLogCapture", null);
            INSTANCE.start();

            // 反射取得 root logger 並附加
            org.apache.logging.log4j.core.Logger rootLogger =
                    (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            rootLogger.addAppender(INSTANCE);

        } catch (Throwable e) {
            // 不能讓 Appender 安裝失敗影響模組啟動
            System.err.println("[BrCrashReporter] Failed to install log capture appender: " + e.getMessage());
        }
    }

    /** 卸載 Appender（伺服器關閉時呼叫）。 */
    public static synchronized void uninstall() {
        if (INSTANCE == null) return;
        try {
            org.apache.logging.log4j.core.Logger rootLogger =
                    (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            rootLogger.removeAppender(INSTANCE);
            INSTANCE.stop();
            INSTANCE = null;
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────
    //  核心：接收日誌事件
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void append(LogEvent event) {
        try {
            Level lvl = event.getLevel();

            // 只捕捉 WARN 以上（過濾 DEBUG/INFO 噪音）
            if (lvl.intLevel() > Level.WARN.intLevel()) return;

            String ts = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getTimeMillis()),
                    ZoneId.systemDefault()
            ).format(DT_FMT);

            String loggerName = shortenLogger(event.getLoggerName());
            String msg = event.getMessage().getFormattedMessage();

            // 附加 Throwable（如果有）
            if (event.getThrown() != null) {
                msg += " | " + event.getThrown().getClass().getSimpleName()
                        + ": " + event.getThrown().getMessage();
            }

            boolean isError = lvl.intLevel() <= Level.ERROR.intLevel();
            LogEntry entry = new LogEntry(ts, lvl.name(), loggerName, msg, isError);

            buffer.addLast(entry);

            // 環形：保持上限
            while (buffer.size() > RING_SIZE) {
                buffer.pollFirst();
            }

        } catch (Throwable ignored) {
            // Appender 不能拋出例外
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  查詢 API
    // ─────────────────────────────────────────────────────────────────

    /** 取得最近的日誌條目（最新在後）。 */
    public static List<LogEntry> getRecentEntries() {
        if (INSTANCE == null) return List.of();
        return new ArrayList<>(INSTANCE.buffer);
    }

    /** 只取錯誤級別條目。 */
    public static List<LogEntry> getRecentErrors() {
        if (INSTANCE == null) return List.of();
        return INSTANCE.buffer.stream()
                .filter(LogEntry::isError)
                .toList();
    }

    /** 清空緩衝（崩潰報告生成後可呼叫）。 */
    public static void clear() {
        if (INSTANCE != null) INSTANCE.buffer.clear();
    }

    // ─────────────────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────────────────

    /** 縮短過長的 logger 名稱，保留最後 3 個節。 */
    private static String shortenLogger(String name) {
        if (name == null) return "?";
        String[] parts = name.split("\\.");
        if (parts.length <= 3) return name;
        return "..." + parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
