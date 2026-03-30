# RPC Server

> 所屬：L1-sidecar > L2-rpc

## 概述

`RpcServer` 類別實作 JSON-RPC 2.0 伺服器，透過 stdio 與 Java `SidecarBridge` 通訊，每行一個 JSON 訊息。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `RpcServer` | `src/rpc-server.ts` | JSON-RPC 2.0 伺服器主類別 |
| `JsonRpcRequest` | `src/rpc-server.ts` | 請求介面（method, params, id） |
| `JsonRpcResponse` | `src/rpc-server.ts` | 回應介面（result 或 error） |
| `JsonRpcNotification` | `src/rpc-server.ts` | 通知介面（無 id，無需回應） |
| `RPC_ERRORS` | `src/rpc-server.ts` | 標準錯誤碼常數 |

## 核心函式

### `RpcServer.method(name, handler)`
- **參數**: `name: string` — 方法名稱；`handler: RpcHandler` — 處理函式
- **回傳**: `this`（鏈式呼叫）
- **說明**: 註冊一個 RPC 方法處理器，支援同步與非同步 handler

### `RpcServer.start()`
- **參數**: 無
- **回傳**: `void`
- **說明**: 建立 readline 介面並開始監聽 stdin。若已在執行中則忽略

### `RpcServer.startWithRL(rl)`
- **參數**: `rl: readline.Interface` — 既有的 readline 介面
- **回傳**: `void`
- **說明**: 使用外部提供的 readline 介面啟動，用於模式偵測後重用

### `RpcServer.processLine(line)`
- **參數**: `line: string` — 一行 JSON 文字
- **回傳**: `void`
- **說明**: 外部注入一行輸入進行處理，用於重新處理緩衝的首行

### `RpcServer.notify(method, params)`
- **參數**: `method: string`；`params: Record<string, unknown>`
- **回傳**: `void`
- **說明**: 發送 JSON-RPC 通知（無 id），Java 端記錄為 `[Sidecar Notification]`

### `RpcServer.stop()`
- **參數**: 無
- **回傳**: `void`
- **說明**: 關閉 readline 介面並停止伺服器

## 型別定義

```typescript
type RpcHandler = (params: Record<string, unknown>) => unknown | Promise<unknown>;
```

## 錯誤碼

| 代碼 | 名稱 | 說明 |
|------|------|------|
| -32700 | `PARSE_ERROR` | JSON 解析失敗 |
| -32600 | `INVALID_REQUEST` | 缺少或無效的 `method` 欄位 |
| -32601 | `METHOD_NOT_FOUND` | 方法未註冊 |
| -32602 | `INVALID_PARAMS` | 參數驗證失敗 |
| -32603 | `INTERNAL_ERROR` | handler 執行時拋出例外 |

## 協定細節

- 每個請求/回應為一行 JSON，以 `\n` 分隔
- `shutdown` 方法為內建處理，回應後延遲 100ms 結束進程
- handler 拋出的 Error 會被捕獲並包裝為 `INTERNAL_ERROR`，若 Error 帶有 `.data` 屬性則一併回傳

## 關聯接口
- 被依賴 <-- [`index.ts`](../index.md)（建立並啟動 RpcServer）
- 被依賴 <-- [`pipeline.ts`](../L2-pipeline/L3-pipeline-core.md)（透過 progress 回呼觸發 notify）
