# BinaryRpcCodec 與 SharedMemoryBridge

> 所屬：L1-api > L2-sidecar

## 概述

提供高效能的 Sidecar 資料傳輸管道：BinaryRpcCodec 在 JSON-RPC 語意上加入 length-prefixed 二進制框架；SharedMemoryBridge 使用 mmap 實現零拷貝共享記憶體傳輸。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BinaryRpcCodec` | `com.blockreality.api.sidecar` | 二進制 RPC 編解碼器（length-prefixed） |
| `SharedMemoryBridge` | `com.blockreality.api.sidecar` | mmap 共享記憶體橋接器（SeqLock 同步） |

## BinaryRpcCodec

### 線路格式

```
[4 bytes: payload length (big-endian)] [1 byte: mode] [N bytes: payload]
```

- `MODE_JSON (0x00)`：UTF-8 JSON 字串（向後相容）
- `MODE_MSGPACK (0x01)`：`[4B headerLen][header JSON][binary voxel data]`

### 核心方法

#### `writeRequest(JsonObject)`
- **說明**：以 JSON 模式寫入 RPC 請求

#### `writeVoxelRequest(JsonObject header, byte[] voxelData)`
- **說明**：以 MessagePack 模式寫入含 RLE 壓縮體素資料的請求

#### `readResponse()`
- **回傳**：`JsonObject`
- **說明**：讀取完整 RPC 回應，自動辨識 JSON/MessagePack 模式

#### `rleEncode(int[])` / `rleDecode(byte[], int)`
- **說明**：體素 material ID 的 RLE 壓縮/解壓縮（varint count + int32 value）

### 限制

- 最大 payload：16 MB
- 緩衝區：65,536 bytes（BufferedInputStream/OutputStream）

## SharedMemoryBridge

### 記憶體佈局

```
[Header: 64 bytes]
  magic (4B): 0x42524D4D ("BRMM")
  version (4B): 1
  writerPid (4B)
  sequenceNumber (8B): 單調遞增序號
  dataOffset (4B): 固定 64
  dataLength (4B)
  flags (4B): dirty/ready/error
  reserved (32B)
[Data: N bytes]
  RLE 壓縮體素資料 or FEM 結果矩陣
```

### SeqLock 同步機制

- **寫入端**：序號設為奇數（寫入中）→ 寫入資料 → 序號設為偶數（完成）→ `buffer.force()`
- **讀取端**：讀取 seq1 → 若奇數則 spin wait → 讀取資料 → 讀取 seq2 → seq1 == seq2 則有效，否則重試（最多 5 次）

### 核心方法

#### `open()`
- **說明**：建立或開啟共享記憶體檔案（`java.io.tmpdir/blockreality/shm_{name}.brshm`）

#### `writeVoxelData(byte[] data, long sequenceNumber)`
- **說明**：SeqLock 寫入協議，完成後呼叫 `buffer.force()`

#### `readVoxelData()`
- **回傳**：`byte[]` 或 `null`（資料不一致/未就緒）

#### `hasNewData(long lastKnownSequence)`
- **說明**：檢查是否有比已知序號更新的資料

### 規格

- 預設大小：64 MB
- 位元組順序：Little Endian
- 檔案路徑：透過 `getMappedFilePath()` 取得，供 sidecar 端開啟同一檔案

## 關聯接口

- 被依賴 ← [SidecarBridge](L3-bridge.md) — 作為替代傳輸管道
- 依賴 → TypeScript sidecar（MctoNurbs-review）
