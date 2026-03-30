# L2-rpc：RPC 通訊系統

> 所屬：L1-sidecar > L2-rpc

## 概述

RPC 子模組實作了 JSON-RPC 2.0 伺服器，透過 stdin/stdout 與 Java `SidecarBridge` 進行行式（line-delimited）雙向通訊。

## 通訊模型

```
Java SidecarBridge          stdio           Node.js RpcServer
      |                                           |
      |-- JSON-RPC Request (一行 JSON) ---------->|
      |                                           |-- 執行 handler
      |<--- JSON-RPC Notification (progress) -----|
      |<--- JSON-RPC Response (一行 JSON) --------|
      |                                           |
      |-- {"method":"shutdown"} ----------------->|
      |<--- {"result":{"shutdown":true}} ---------|
      |                                      process.exit(0)
```

## 已註冊的方法

| 方法名稱 | 說明 | 參數型別 |
|----------|------|----------|
| `ping` | 健康檢查 | 無 |
| `dualContouring` | 方塊轉 STEP 主流程 | `DualContouringParams` |
| `shutdown` | 優雅關閉（內建） | 無 |

## 驗證規則（`dualContouring`）

- `params.blocks` 必須為非空陣列
- 方塊數量不得超過 `MAX_BLOCK_COUNT`（65,536）
- `params.options.outputPath` 為必填
- `resolution` 須在 1~4 範圍內

## 詳細文件

| 文件 | 說明 |
|------|------|
| [L3-rpc-server.md](L3-rpc-server.md) | RpcServer 類別與 JSON-RPC 協定細節 |
