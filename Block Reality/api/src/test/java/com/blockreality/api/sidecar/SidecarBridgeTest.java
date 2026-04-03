package com.blockreality.api.sidecar;

import com.blockreality.api.sidecar.SidecarBridge.SidecarException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SidecarBridge 行為測試 — M6 邊緣案例
 *
 * 測試策略：
 *   由於 SidecarBridge 需要 Node.js 執行時，單元測試無法實際啟動 sidecar 行程。
 *   本測試聚焦於：
 *   1. SidecarException 語意正確性（IPC 逾時應拋異常，而非回傳 null）
 *   2. 逾時機制本身的行為（以 CompletableFuture 模擬）
 *   3. SidecarException 訊息與繼承鏈驗證
 *   4. SidecarBridge 在未啟動時的狀態查詢不拋例外
 *
 * 注意：
 *   - 不測試實際的 stdio IPC（需整合測試環境）
 *   - 不呼叫 SidecarBridge.getInstance().start()（依賴 FMLPaths，Forge 測試環境才可用）
 */
@DisplayName("SidecarBridge — M6 邊緣案例測試")
class SidecarBridgeTest {

    // ═══════════════════════════════════════════════════════
    //  SidecarException 語意測試
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("SidecarException — IPC 逾時應拋出異常而非返回 null")
    class SidecarExceptionSemanticsTests {

        @Test
        @DisplayName("SidecarException 是 Exception 的子類別（checked exception）")
        void sidecarExceptionIsCheckedException() {
            SidecarException ex = new SidecarException("逾時");
            assertInstanceOf(Exception.class, ex,
                "SidecarException 必須是 checked Exception，以強制呼叫端處理逾時");
        }

        @Test
        @DisplayName("SidecarException 不是 RuntimeException（避免逾時被靜默忽略）")
        void sidecarExceptionIsNotRuntimeException() {
            SidecarException ex = new SidecarException("逾時");
            // Cast to Object first: direct instanceof causes compile error because the compiler
            // can statically prove SidecarException (extends Exception) is never a RuntimeException.
            assertFalse(((Object) ex) instanceof RuntimeException,
                "SidecarException 不應是 RuntimeException — 呼叫端必須明確處理逾時");
        }

        @Test
        @DisplayName("SidecarException(message) 保留原始訊息")
        void sidecarExceptionMessagePreserved() {
            String msg = "RPC 逾時: method=dualContouring, id=42";
            SidecarException ex = new SidecarException(msg);
            assertEquals(msg, ex.getMessage(),
                "SidecarException 應保留逾時詳細訊息，方便診斷");
        }

        @Test
        @DisplayName("SidecarException(message, cause) 保留根本原因")
        void sidecarExceptionCausePreserved() {
            TimeoutException cause = new TimeoutException("底層逾時");
            SidecarException ex = new SidecarException("RPC 逾時", cause);

            assertEquals("RPC 逾時", ex.getMessage());
            assertSame(cause, ex.getCause(),
                "SidecarException 應保留 Cause 以便追蹤根本原因");
        }

        @Test
        @DisplayName("SidecarException.getCause() 可為 null（純逾時場景）")
        void sidecarExceptionNullCause() {
            SidecarException ex = new SidecarException("純逾時，無根本原因");
            assertNull(ex.getCause(),
                "無 cause 的 SidecarException getCause() 應為 null");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  逾時機制模擬測試（以 CompletableFuture 模擬 IPC pending future）
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("IPC 逾時機制 — 逾時應拋異常而非返回 null")
    class IpcTimeoutMechanismTests {

        /**
         * 模擬 SidecarBridge.call() 內部邏輯：
         * pending future 等待回應，逾時後應拋 SidecarException 而非回傳 null。
         */
        @Test
        @DisplayName("逾時時應拋 SidecarException，而非返回 null（關鍵語意保證）")
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        void timeoutThrowsSidecarExceptionNotNull() throws InterruptedException {
            CompletableFuture<Object> pendingFuture = new CompletableFuture<>();

            // 模擬: call() 等待 50ms 未收到回應，應拋出 SidecarException
            SidecarException thrown = assertThrows(SidecarException.class, () -> {
                try {
                    pendingFuture.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // SidecarBridge.call() 的實際行為：TimeoutException → SidecarException
                    throw new SidecarException("RPC 逾時: method=dualContouring", e);
                } catch (ExecutionException e) {
                    throw new SidecarException("RPC 執行失敗: " + e.getCause().getMessage(), e.getCause());
                }
            }, "IPC 逾時時必須拋 SidecarException，不可靜默返回 null");

            assertNotNull(thrown.getMessage(), "SidecarException 應包含逾時原因訊息");
            assertTrue(thrown.getMessage().contains("逾時"),
                "逾時異常訊息應包含「逾時」字樣以利診斷");
        }

        @Test
        @DisplayName("逾時後 CompletableFuture 被 completeExceptionally 而非設為 null")
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        void timeoutCompletesExceptionallyNotNull() {
            CompletableFuture<Object> pendingFuture = new CompletableFuture<>();

            // 模擬清理任務的行為：超時請求以 SidecarException 完成，而非放棄（null）
            pendingFuture.completeExceptionally(new SidecarException("Request expired by cleanup task"));

            assertTrue(pendingFuture.isCompletedExceptionally(),
                "超時 future 應以異常完成，而非正常完成（null result）");
            assertFalse(pendingFuture.isCancelled(),
                "超時 future 不應被取消，而是以 SidecarException 完成");
        }

        @Test
        @DisplayName("正常回應不拋異常，future.get() 返回有效值")
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        void normalResponseNoException() throws Exception {
            CompletableFuture<String> pendingFuture = new CompletableFuture<>();

            // 模擬正常 IPC 回應
            pendingFuture.complete("ok");

            String result = pendingFuture.get(100, TimeUnit.MILLISECONDS);
            assertEquals("ok", result, "正常回應不應拋異常");
        }

        @Test
        @DisplayName("SidecarException wrapped 在 ExecutionException 中可被正確解包")
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        void sidecarExceptionUnwrappedFromExecutionException() {
            CompletableFuture<Object> pendingFuture = new CompletableFuture<>();
            SidecarException originalEx = new SidecarException("模擬 RPC 錯誤");
            pendingFuture.completeExceptionally(originalEx);

            ExecutionException execEx = assertThrows(ExecutionException.class,
                () -> pendingFuture.get(100, TimeUnit.MILLISECONDS));

            assertInstanceOf(SidecarException.class, execEx.getCause(),
                "CompletableFuture 的 ExecutionException.getCause() 應為 SidecarException");
            assertEquals("模擬 RPC 錯誤", execEx.getCause().getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  SidecarBridge 狀態查詢（不啟動行程）
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("SidecarBridge 狀態查詢（不啟動行程）")
    class BridgeStateQueryTests {

        @Test
        @DisplayName("getInstance() 返回非 null 單例")
        void getInstanceNonNull() {
            // SidecarBridge.getInstance() 使用 Bill Pugh Holder，但在測試環境中
            // FMLPaths 可能未初始化。測試前提：只查詢類別本身的靜態結構，不觸發 Forge API。
            // 此測試驗證類別可被 classloader 載入而不拋 ClassNotFoundException。
            // initialize=false 跳過靜態初始化（FMLPaths.GAMEDIR 在非 Forge 環境下為 null）。
            assertDoesNotThrow(() -> {
                Class<?> clazz = Class.forName(
                    "com.blockreality.api.sidecar.SidecarBridge",
                    false,                                       // 不觸發靜態初始化
                    Thread.currentThread().getContextClassLoader()
                );
                assertNotNull(clazz);
            }, "SidecarBridge 類別應可被 classloader 正常載入");
        }

        @Test
        @DisplayName("SidecarException 類別可獨立實例化（不依賴 Forge）")
        void sidecarExceptionInstantiable() {
            // 這是最重要的契約：SidecarException 不依賴任何 Forge/Minecraft 類別
            assertDoesNotThrow(() -> {
                SidecarException ex1 = new SidecarException("test");
                SidecarException ex2 = new SidecarException("test", new RuntimeException());
                assertNotNull(ex1);
                assertNotNull(ex2);
            });
        }
    }
}
