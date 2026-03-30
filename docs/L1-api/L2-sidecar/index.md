# Sidecar 橋接系統

> 所屬：[L1-api](../index.md)

## 概述

Sidecar 橋接系統負責 Java 端與 TypeScript Node.js sidecar 之間的跨程序通訊。
透過 stdio JSON-RPC 2.0 協議傳送 RPC 請求，支援二進制編碼優化與共享記憶體高速通道，
實現體素資料的高效傳輸與 NURBS/STEP CAD 匯出功能。

## 元件組成

| 元件 | 說明 |
|------|------|
| `SidecarBridge` | 核心橋接器 — 生命週期管理、JSON-RPC 通訊、自動重連 |
| `BinaryRpcCodec` | 二進制 RPC 編解碼 — length-prefixed 框架 + RLE 體素壓縮 |
| `SharedMemoryBridge` | 共享記憶體 — mmap 零拷貝大型資料傳輸 |
| `NodeInstaller` | Node.js 自動安裝器 — sidecar.js 解壓縮 + portable Node.js 下載 |

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-bridge](L3-bridge.md) | SidecarBridge 橋接器與 NodeInstaller |
| [L3-rpc-codec](L3-rpc-codec.md) | BinaryRpcCodec 與 SharedMemoryBridge |
