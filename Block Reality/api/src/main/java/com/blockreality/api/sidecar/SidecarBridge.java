// src/main/java/com/blockreality/api/sidecar/SidecarBridge.java
package com.blockreality.api.sidecar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java ↔ TypeScript Sidecar 橋接器（JSON-RPC 2.0 over stdio）
 * 使用方式：
 *   SidecarBridge bridge = SidecarBridge.getInstance();
 *   bridge.start();
 *   JsonObject result = bridge.call("dualContouring", params, 10_000);
 *   bridge.stop();
 *
 * @since 1.0.0
 */
public class SidecarBridge {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Sidecar");
    private static final Gson GSON = new Gson();

    // v3-fix: 添加請求超時時間常數，用於定期清理
    private static final long REQUEST_TIMEOUT_MS = 30000;

    // ─── 單例（v2.0-fix：改用 Bill Pugh Holder，避免 DCL 指令重排序風險）───
    private static class Holder {
        private static final SidecarBridge INSTANCE = new SidecarBridge();
    }

    // ★ new-fix N7: 移除原本的 readResolve() — SidecarBridge 未實作 Serializable，
    // 該方法永遠不會被 JVM 序列化機制呼叫，是誤導性的死代碼。

    private Process nodeProcess;
    private PrintWriter writer;
    private BufferedReader reader;

    // ─── writer 同步鎖（v2.0-fix：避免多執行緒同時寫入 stdout 導致 JSON 交錯）───
    // v3-fix: 重命名為 writeSyncLock 避免命名誤導
    private final Object writeSyncLock = new Object();

    // v3-fix: 添加狀態鎖，保護 running 和 writer 的原子性檢查
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    /**
     * RPC id 計數器。
     * ★ Round 5 fix: 使用正數模運算防止 Integer.MAX_VALUE 溢位後產生負 ID，
     * JSON-RPC 2.0 spec 建議 id 為正整數。getAndUpdate 保證原子性。
     */
    private final AtomicInteger rpcId = new AtomicInteger(0);

    // v3-fix: 使用帶時間戳的封裝類替代直接使用 CompletableFuture
    private static class PendingEntry {
        final CompletableFuture<JsonObject> future;
        final long createTime;

        PendingEntry(CompletableFuture<JsonObject> future) {
            this.future = future;
            this.createTime = System.currentTimeMillis();
        }

        boolean isExpired(long now, long timeout) {
            return now - createTime > timeout;
        }
    }

    // 待回應的 Future 表（rpcId → PendingEntry）
    // v3-fix: 改用 PendingEntry 封裝類
    private final ConcurrentHashMap<Integer, PendingEntry> pending =
            new ConcurrentHashMap<>();

    // ★ R3-8 fix: cleanupExecutor 改為延遲建立，避免 stop() 後永久關閉、
    // start() 無法重啟的生命週期問題。同時避免未 start() 時就有後台任務在跑。
    private ScheduledExecutorService cleanupExecutor;
    private java.util.concurrent.ScheduledFuture<?> cleanupFuture;

    // 讀取執行緒
    private volatile Thread readerThread;
    private volatile boolean running = false;

    /**
     * ★ v4-fix: 安全白名單 — sidecar 腳本必須位於 GAMEDIR/blockreality/sidecar/ 目錄下。
     * ★ review-fix #1: 原先設為 GAMEDIR/sidecar/，與 start() 無參版本的 defaultScript
     *   (GAMEDIR/blockreality/sidecar/dist/sidecar.js) 不匹配，導致永遠觸發 SecurityException。
     */
    private static final Path SIDECAR_BASE_DIR =
        FMLPaths.GAMEDIR.get().resolve("blockreality").resolve("sidecar");

    private SidecarBridge() {
        // ★ R3-8 fix: 不再在建構子中啟動清理任務，
        // 改為在 start() 中動態建立 executor + 排程清理任務。
    }

    public static SidecarBridge getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 檢查 sidecar 子行程是否正在執行。
     *
     * @return true 若 sidecar 已啟動且連線中
     * @since 1.0.0
     */
    public boolean isRunning() {
        stateLock.readLock().lock();
        try {
            return running && nodeProcess != null && nodeProcess.isAlive();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * ★ v4-fix: 驗證 sidecar 腳本路徑安全性。
     * 腳本必須位於 GAMEDIR/sidecar/ 目錄下，且不得包含路徑穿越。
     *
     * @param script 腳本路徑
     * @throws SecurityException 若路徑不在白名單目錄下
     * @throws IOException 若路徑正規化失敗
     */
    private static void validateScriptPath(Path script) throws IOException {
        if (script == null) {
            throw new SecurityException("[SidecarBridge] sidecarScript 不可為 null");
        }
        Path normalized = script.toAbsolutePath().normalize();
        Path baseDirNormalized = SIDECAR_BASE_DIR.toAbsolutePath().normalize();

        if (!normalized.startsWith(baseDirNormalized)) {
            throw new SecurityException(String.format(
                "[SidecarBridge] 安全限制：腳本路徑 '%s' 不在允許目錄 '%s' 下。" +
                "請將 sidecar 腳本放在 GAMEDIR/sidecar/ 目錄中。",
                normalized, baseDirNormalized));
        }

        // 防止符號連結逸出
        Path realPath = normalized.toRealPath();
        if (!realPath.startsWith(baseDirNormalized)) {
            throw new SecurityException(String.format(
                "[SidecarBridge] 安全限制：腳本實際路徑 '%s'（符號連結解析後）不在允許目錄下。",
                realPath));
        }

        LOGGER.debug("[SidecarBridge] 腳本路徑驗證通過: {}", realPath);
    }

    // v3-fix: 修改點4 - 健康檢查相關欄位
    private volatile long lastSuccessfulCleanupTime = System.currentTimeMillis();
    private volatile long cleanupSuccessCount = 0;
    private volatile long cleanupErrorCount = 0;
    private static final long CLEANUP_HEALTH_TIMEOUT_MS = 300000; // 5分鐘健康超時

    // v3-fix: 修改點4 - 添加定期清理過期請求的方法（帶健康檢查）
    private void startCleanupTask() {
        cleanupFuture = cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                AtomicInteger cleanedCount = new AtomicInteger(0);

                pending.entrySet().removeIf(entry -> {
                    PendingEntry pendingEntry = entry.getValue();
                    if (pendingEntry.isExpired(now, REQUEST_TIMEOUT_MS * 2)) {
                        pendingEntry.future.completeExceptionally(
                            new SidecarException("Request expired by cleanup task")
                        );
                        LOGGER.warn("清理過期請求: id={}", entry.getKey());
                        cleanedCount.incrementAndGet();
                        return true;
                    }
                    return false;
                });

                // v3-fix: 修改點4 - 記錄成功清理時間
                lastSuccessfulCleanupTime = now;
                cleanupSuccessCount++;

                if (cleanedCount.get() > 0) {
                    LOGGER.debug("Cleanup task completed: {} expired requests removed", cleanedCount.get());
                }

            } catch (RuntimeException e) {
                // v3-fix: 修改點4 - 記錄錯誤計數
                cleanupErrorCount++;
                LOGGER.error("清理任務執行異常 (error count: {})", cleanupErrorCount, e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // v3-fix: 修改點4 - 添加健康檢查方法
    /**
     * 檢查清理任務的健康狀態
     * @return true if cleanup task is healthy
     */
    public boolean isCleanupHealthy() {
        long now = System.currentTimeMillis();
        long timeSinceLastSuccess = now - lastSuccessfulCleanupTime;

        if (timeSinceLastSuccess > CLEANUP_HEALTH_TIMEOUT_MS) {
            LOGGER.warn("Cleanup task unhealthy: last successful cleanup was {} ms ago",
                timeSinceLastSuccess);
            return false;
        }

        if (cleanupErrorCount > cleanupSuccessCount * 2 && cleanupErrorCount > 10) {
            LOGGER.warn("Cleanup task unhealthy: {} errors vs {} successes",
                cleanupErrorCount, cleanupSuccessCount);
            return false;
        }

        return true;
    }

    // v3-fix: 修改點4 - 獲取清理任務統計資訊
    /**
     * 獲取清理任務的統計資訊
     */
    public CleanupStats getCleanupStats() {
        return new CleanupStats(
            lastSuccessfulCleanupTime,
            cleanupSuccessCount,
            cleanupErrorCount,
            pending.size()
        );
    }

    // v3-fix: 修改點4 - 清理任務統計資訊記錄類
    public record CleanupStats(
        long lastSuccessfulCleanupTime,
        long successCount,
        long errorCount,
        int pendingRequestCount
    ) {
        public long getTimeSinceLastSuccessMs() {
            return System.currentTimeMillis() - lastSuccessfulCleanupTime;
        }

        public boolean isHealthy() {
            return getTimeSinceLastSuccessMs() <= CLEANUP_HEALTH_TIMEOUT_MS &&
                   (errorCount <= successCount * 2 || errorCount <= 10);
        }

        @Override
        public String toString() {
            return String.format(
                "CleanupStats{lastSuccess=%dms ago, successes=%d, errors=%d, pending=%d, healthy=%b}",
                getTimeSinceLastSuccessMs(), successCount, errorCount, pendingRequestCount, isHealthy()
            );
        }
    }

    // v3-fix: 修改點4 - 重置清理任務健康狀態（用於恢復後）
    public void resetCleanupHealth() {
        lastSuccessfulCleanupTime = System.currentTimeMillis();
        cleanupSuccessCount = 0;
        cleanupErrorCount = 0;
        LOGGER.info("Cleanup health stats reset");
    }

    /**
     * 啟動 Node.js sidecar 子行程
     *
     * @param nodeExecutable Node.js 執行檔路徑（null 表示用系統 PATH 的 node）
     * @param sidecarScript  sidecar 入口腳本絕對路徑
     */
    // ★ review-fix #9: 移除 synchronized — 完全依靠 stateLock.writeLock() 保護狀態，
    // 避免 this + stateLock 兩個鎖對象的巢狀取得造成 deadlock 風險
    public void start(String nodeExecutable, Path sidecarScript) throws IOException {
        // ★ v4-fix: 路徑白名單驗證 — 防止任意腳本注入
        validateScriptPath(sidecarScript);
        // v3-fix: 使用寫鎖保護狀態檢查和修改
        stateLock.writeLock().lock();
        try {
            if (running) {
                LOGGER.warn("SidecarBridge 已在執行中，忽略重複啟動");
                return;
            }

            String nodePath = (nodeExecutable != null) ? nodeExecutable : resolveNodeFromPath();
            LOGGER.info("啟動 Sidecar: {} {}", nodePath, sidecarScript);

            ProcessBuilder pb = new ProcessBuilder(
                    nodePath,
                    sidecarScript.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(false);                   // stderr 分離，避免污染 stdout JSON
            pb.environment().put("BLOCKREALITY_MODE", "sidecar");
            pb.environment().put("NODE_ENV", "production");

            nodeProcess = pb.start();

            // stderr → 轉發到 Log4j
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(nodeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        LOGGER.warn("[Sidecar STDERR] {}", line);
                    }
                } catch (IOException e) {
                    // 子行程結束，忽略
                }
            }, "BR-Sidecar-Stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            writer = new PrintWriter(
                    new OutputStreamWriter(nodeProcess.getOutputStream(), StandardCharsets.UTF_8),
                    true  // autoFlush
            );
            reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream(), StandardCharsets.UTF_8)
            );

            running = true;

            // ★ R3-8 fix: 每次 start() 重新建立 cleanupExecutor，
            // 避免上次 stop() 已 shutdown 後無法再排程的問題。
            if (cleanupExecutor == null || cleanupExecutor.isShutdown()) {
                cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "BR-Sidecar-Cleanup");
                    t.setDaemon(true);
                    return t;
                });
            }
            startCleanupTask();
            resetCleanupHealth();

            // 非同步讀取回應的執行緒
            readerThread = new Thread(this::readLoop, "BR-Sidecar-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("Sidecar 啟動成功（PID: {}）", nodeProcess.pid());
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * 異步啟動 Sidecar — 會自動處理 Node.js 下載與 sidecar.js 解壓縮。
     * （不會阻塞伺服器主執行緒）
     */
    public CompletableFuture<Void> startAsync() {
        return NodeInstaller.ensureNodeJsAsync().thenAcceptAsync(nodePath -> {
            try {
                Path script = NodeInstaller.ensureSidecarScriptExists();
                start(nodePath, script);
            } catch (Exception e) {
                LOGGER.error("自動啟動 Sidecar 失敗", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * @deprecated 改用 startAsync() 以獲得全自動的依賴管理與安裝體驗。
     */
    @Deprecated
    public void start() throws IOException {
        Path defaultScript = FMLPaths.GAMEDIR.get()
                .resolve("blockreality")
                .resolve("sidecar")
                .resolve("dist")
                .resolve("sidecar.js");
        start(null, defaultScript);
    }

    /**
     * 發送 JSON-RPC 2.0 請求並等待回應
     *
     * @param method     RPC 方法名稱
     * @param params     參數（JsonObject）
     * @param timeoutMs  逾時毫秒數
     * @return result 欄位的 JsonObject（若 error 則拋 SidecarException）
     */
    public JsonObject call(String method, JsonObject params, long timeoutMs)
            throws SidecarException, InterruptedException {

        // ★ new-fix N1: readLock 只保護「running 檢查 + 送出請求」這段快速操作。
        // 原本把 future.get()（最多 30s 阻塞）包在 readLock 內，導致 stop() 的 writeLock
        // 在等待期間永遠無法取得 — 服務器關閉時最多被阻塞 timeoutMs 秒。
        // 解法：在 readLock 內只做 O(1) 的 running check + request send，然後釋放 lock，
        // 再在 lock 外 await future。
        int id = -1;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        stateLock.readLock().lock();
        try {
            if (!running || writer == null) {
                throw new SidecarException("Sidecar 未啟動或 writer 尚未初始化");
            }

            // ★ Round 5 fix: 保證正數 ID，防止 Integer.MAX_VALUE 溢位成負數
            id = rpcId.updateAndGet(prev -> (prev >= Integer.MAX_VALUE - 1) ? 1 : prev + 1);
            // v3-fix: 使用 PendingEntry 封裝，包含時間戳
            pending.put(id, new PendingEntry(future));

            // 組裝 JSON-RPC 2.0 請求
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("method", method);
            request.add("params", params);
            request.addProperty("id", id);

            // v3-fix: 改進 writeLock 使用，添加異常處理和錯誤檢查
            synchronized (writeSyncLock) {
                try {
                    String json = GSON.toJson(request);
                    writer.println(json);
                    writer.flush(); // v3-fix: 確保立即發送

                    // v3-fix: 檢查 writer 錯誤狀態
                    if (writer.checkError()) {
                        pending.remove(id);
                        throw new SidecarException("Writer encountered error");
                    }
                } catch (RuntimeException e) {
                    pending.remove(id);
                    throw new SidecarException("Failed to send request", e);
                }
            }
            // ★ N1: 請求已送出，立即釋放 readLock，不在鎖內阻塞等待
        } catch (SidecarException e) {
            if (id != -1) pending.remove(id);
            throw e;
        } finally {
            stateLock.readLock().unlock();
        }

        // ★ N1: 在 readLock 外 await，stop() 可隨時取得 writeLock
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new SidecarException("RPC 逾時: method=" + method + ", id=" + id);
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new SidecarException("RPC 執行失敗: " + e.getCause().getMessage(), e.getCause());
        } catch (RuntimeException e) {
            pending.remove(id);
            throw new SidecarException("RPC 呼叫失敗: " + e.getMessage(), e);
        }
    }

    /** stdout 讀取迴圈（在獨立執行緒運行） */
    private void readLoop() {
        try {
            String line;
            // v2.0-fix：加入 Thread.interrupted() 檢查，配合 stop() 的 interrupt 優雅退出
            while (running && !Thread.currentThread().isInterrupted()
                   && (line = reader.readLine()) != null) {
                final String payload = line.trim();
                if (payload.isEmpty()) continue;

                JsonObject response;
                try {
                    response = GSON.fromJson(payload, JsonObject.class);
                } catch (RuntimeException e) {
                    LOGGER.error("Sidecar 回傳非 JSON: {}", payload);
                    continue;
                }

                // 解析 id
                if (!response.has("id") || response.get("id").isJsonNull()) {
                    // 通知事件（notification），暫不處理
                    LOGGER.debug("[Sidecar Notification] {}", payload);
                    continue;
                }

                int id = response.get("id").getAsInt();
                // v3-fix: 從 PendingEntry 中獲取 future
                PendingEntry entry = pending.remove(id);
                if (entry == null) {
                    LOGGER.warn("收到未知 RPC id {} 的回應", id);
                    continue;
                }

                CompletableFuture<JsonObject> future = entry.future;
                if (response.has("error")) {
                    future.completeExceptionally(
                        new SidecarException("RPC error: " + response.get("error").toString())
                    );
                } else {
                    future.complete(response.has("result")
                            ? response.get("result").getAsJsonObject()
                            : new JsonObject());
                }
            }
        } catch (IOException e) {
            if (running) LOGGER.error("Sidecar 讀取中斷", e);
        } finally {
            // 通知所有等待中的 future 失敗
            // v3-fix: 從 PendingEntry 中獲取 future
            pending.forEach((id, entry) ->
                entry.future.completeExceptionally(new SidecarException("Sidecar 連線中斷"))
            );
            pending.clear();
        }
    }

    /** 停止 sidecar 子行程（v3-fix：修正資源清理順序，確保安全關閉） */
    public void stop() {
        // v3-fix: 使用寫鎖保護狀態修改
        stateLock.writeLock().lock();
        try {
            running = false;

            // v3-fix: 1. 先關閉 writer，讓對端知道不再發送
            if (writer != null) {
                // 傳送 shutdown 通知
                try {
                    JsonObject shutdown = new JsonObject();
                    shutdown.addProperty("jsonrpc", "2.0");
                    shutdown.addProperty("method", "shutdown");
                    shutdown.add("id", null);
                    writer.println(GSON.toJson(shutdown));
                    writer.flush();
                } catch (RuntimeException e) {
                    LOGGER.warn("發送 shutdown 通知失敗", e);
                }
                writer.close();
                writer = null;
            }

            // v3-fix: 2. 中斷並等待 reader 執行緒
            if (readerThread != null) {
                readerThread.interrupt();
                try {
                    readerThread.join(5000);
                    // v3-fix: 檢查執行緒是否真正結束
                    if (readerThread.isAlive()) {
                        LOGGER.warn("Reader thread did not terminate gracefully");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // v3-fix: 3. 關閉 reader
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOGGER.error("Error closing reader", e);
                }
                reader = null;
            }

            // v3-fix: 4. 銷毀 process
            if (nodeProcess != null) {
                if (nodeProcess.isAlive()) {
                    nodeProcess.destroy();
                    try {
                        if (!nodeProcess.waitFor(5, TimeUnit.SECONDS)) {
                            nodeProcess.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        nodeProcess.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
                nodeProcess = null;
            }

            // v3-fix: 5. 清理 pending（從 PendingEntry 中獲取 future）
            pending.forEach((id, entry) ->
                entry.future.completeExceptionally(new SidecarException("Bridge stopped"))
            );
            pending.clear();

            // v3-fix: 6. 關閉清理執行器
            // ★ R3-8 fix: null 檢查 — stop() 可能在 start() 之前被呼叫
            if (cleanupExecutor != null) {
                cleanupExecutor.shutdown();
                try {
                    if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        cleanupExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    cleanupExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            LOGGER.info("Sidecar 已停止");
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /** 從系統 PATH 解析 node 執行檔 */
    private String resolveNodeFromPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "node.exe" : "node";
    }

    /** Sidecar 相關例外 */
    public static class SidecarException extends Exception {
        public SidecarException(String message) { super(message); }
        public SidecarException(String message, Throwable cause) { super(message, cause); }
    }
}
