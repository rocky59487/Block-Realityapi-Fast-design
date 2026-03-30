# 撤銷系統

> 所屬：L1-fastdesign > [L2-command](index.md)

## 概述

Fast Design 提供兩套撤銷引擎：`UndoManager`（全量快照式）和 `DeltaUndoManager`（差異式）。兩者皆為執行緒安全設計，使用 `ConcurrentHashMap` + `ConcurrentLinkedDeque`。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `UndoManager` | `command.UndoManager` | 全量快照撤銷（舊版），儲存操作前所有方塊狀態 |
| `DeltaUndoManager` | `command.DeltaUndoManager` | 差異式撤銷/重做（新版），僅儲存實際變更的方塊 |

## UndoManager（全量快照式）

儲存操作前選取區域內所有方塊的完整狀態，適用於 `/fd box`、`/fd extrude`、`/fd rebar-grid` 等基礎指令。

### 資料結構

- `BlockRecord(pos, state, nbt)` — 單一方塊的完整快照
- `UndoSnapshot(records, description)` — 一次操作的快照集合
- 每位玩家維護獨立的 `Deque<UndoSnapshot>` 堆疊

### 核心方法

#### `pushSnapshot(playerId, level, box, desc)`
- **參數**: `UUID` 玩家 ID, `ServerLevel` 世界, `SelectionBox` 選取區域, `String` 描述
- **回傳**: `void`
- **說明**: 擷取選取區域內所有方塊狀態（含 BlockEntity NBT），壓入堆疊。堆疊大小由 `FastDesignConfig.getUndoStackSize()` 限制。

#### `pushSnapshotForPositions(playerId, level, positions, desc)`
- **參數**: `UUID`, `ServerLevel`, `Collection<BlockPos>` 位置清單, `String` 描述
- **回傳**: `void`
- **說明**: 與 `pushSnapshot` 相同但接受任意位置集合（用於 extrude 等非矩形區域操作）

#### `undo(playerId, level)`
- **參數**: `UUID` 玩家 ID, `ServerLevel` 世界
- **回傳**: `int` — 還原的方塊數量
- **說明**: 彈出最新快照，還原方塊狀態與 BlockEntity 資料

#### `onPlayerDisconnect(playerId)`
- **參數**: `UUID` 玩家 ID
- **回傳**: `void`
- **說明**: 清除該玩家所有 undo 記錄以防止記憶體洩漏

## DeltaUndoManager（差異式）

取代舊版全量快照，僅儲存實際被修改的方塊差異記錄，大幅降低記憶體消耗。支援 50 步歷史回退與 Redo，10 萬方塊操作也不卡頓。設計參考 WorldEdit EditSession 差異追蹤機制。

### 資料結構

- `BlockChangeRecord(pos, oldState, oldNbt, newState, newNbt)` — 單一方塊的變更差異
- `DeltaSnapshot(changes, description, timestamp)` — 一次操作的差異集合
- 每位玩家維護獨立的 Undo 堆疊與 Redo 堆疊（最大各 50 步）

### 核心方法

#### `captureBeforeState(level, positions)`
- **參數**: `ServerLevel` 世界, `Collection<BlockPos>` 目標位置
- **回傳**: `Map<BlockPos, BlockChangeRecord>` — 操作前狀態映射
- **說明**: 操作前呼叫，收集所有目標位置的當前狀態

#### `commitChanges(playerId, level, beforeMap, desc)`
- **參數**: `UUID`, `ServerLevel`, `Map<BlockPos, BlockChangeRecord>` 操作前映射, `String` 描述
- **回傳**: `int` — 實際變更的方塊數量
- **說明**: 操作後呼叫，比對前後狀態僅記錄真正變更的方塊。新操作發生時自動清空 Redo 堆疊。

#### `undo(playerId, level)`
- **參數**: `UUID`, `ServerLevel`
- **回傳**: `int` — 還原的方塊數量
- **說明**: 還原到 oldState，同時將當前狀態壓入 Redo 堆疊

#### `redo(playerId, level)`
- **參數**: `UUID`, `ServerLevel`
- **回傳**: `int` — 重做的方塊數量
- **說明**: 重做被撤銷的操作，同時將當前狀態壓入 Undo 堆疊

### 使用模式

```java
// 1. 操作前捕獲
var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

// 2. 執行修改
for (BlockPos pos : positions) {
    level.setBlock(pos, newState, 3);
}

// 3. 提交差異
DeltaUndoManager.commitChanges(playerId, level, beforeMap, "fill concrete");
```

## 兩套引擎的使用場景

| 引擎 | 使用者 | 特點 |
|------|--------|------|
| `UndoManager` | `/fd box`, `/fd extrude`, `/fd rebar-grid`, `/fd save` | 全量快照，簡單直接 |
| `DeltaUndoManager` | `/fd fill`, `/fd replace`, `/fd clear`, `/fd walls`, `/fd paste`, `PastePlacePacket` | 差異記錄，支援 Redo，記憶體效率高 |

## 關聯接口
- 被依賴 ← [FdCommandRegistry](L3-fd-commands.md)（指令系統）
- 被依賴 ← [FdActionPacket](../L2-network/L3-packets.md)（GUI 操作封包）
- 被依賴 ← [PastePlacePacket](../L2-network/L3-packets.md)（貼上放置封包）
