# SidecarBridge

> 所屬：L1-api > L2-sidecar

## 概述

Java 與 TypeScript Sidecar 之間的 JSON-RPC 2.0 over stdio 橋接器，提供完整的生命週期管理、自動重連與健康監控。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `SidecarBridge` | `com.blockreality.api.sidecar` | 單例橋接器（Bill Pugh Holder 模式） |
| `SidecarException` | 內部類別 | Sidecar 通訊相關例外 |
| `PendingEntry` | 內部類別 | 帶時間戳的 RPC 待回應封裝 |
| `CleanupStats` | 內部 record | 清理任務統計資訊 |
| `NodeInstaller` | `com.blockreality.api.sidecar` | Node.js 與 sidecar.js 自動安裝 |

## 核心方法

### `getInstance()`
- **回傳**：`SidecarBridge` 單例
- **說明**：Bill Pugh Holder 模式，避免 DCL 指令重排序風險

### `start(String nodeExecutable, Path sidecarScript)`
- **參數**：Node.js 執行檔路徑（null 使用系統 PATH）、sidecar 腳本路徑
- **說明**：啟動子行程。包含路徑白名單驗證（`GAMEDIR/blockreality/sidecar/`）、stderr 轉發、cleanup 排程

### `startAsync()`
- **回傳**：`CompletableFuture<Void>`
- **說明**：全自動啟動 — 透過 `NodeInstaller` 處理 Node.js 下載與 sidecar.js 解壓縮

### `call(String method, JsonObject params, long timeoutMs)`
- **參數**：RPC 方法名、參數、逾時毫秒數
- **回傳**：`JsonObject`（result 欄位）
- **說明**：發送 JSON-RPC 2.0 請求並等待。行程已死時自動嘗試重連

### `stop()`
- **說明**：優雅關閉 — 發送 shutdown 通知、中斷讀取線程、銷毀子行程、清理所有 pending future

## 安全機制

- **路徑白名單**：`validateScriptPath()` 確保腳本位於 `GAMEDIR/blockreality/sidecar/` 下，防止符號連結逸出
- **RPC ID 正數保證**：`AtomicInteger.updateAndGet()` 防止 `Integer.MAX_VALUE` 溢位

## 自動重連

- 指數退避：1s → 2s → 4s
- 最大重連嘗試：3 次
- Circuit Breaker：連續失敗後冷卻 60 秒
- 在 `call()` 中偵測行程死亡自動觸發

## NodeInstaller

| 方法 | 說明 |
|------|------|
| `ensureSidecarScriptExists()` | 從 JAR 資源擷取 `sidecar.js` 到磁碟 |
| `ensureNodeJsAsync()` | 偵測系統 Node.js 或下載 portable 版本（Windows） |

## 關聯接口

- 被依賴 ← [Fast Design sidecar](../../L1-fastdesign/L2-sidecar/) — NURBS/STEP 匯出
- 依賴 → [BinaryRpcCodec](L3-rpc-codec.md) — 二進制編碼優化
