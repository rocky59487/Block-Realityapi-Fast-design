# UnionFindEngine — 連通性分析引擎

> 所屬：L1-api > L2-physics

## 概述

BFS 連通塊引擎，從錨定點（Anchor）擴散，找出所有失去支撐的懸空方塊。使用 Scan Margin 策略與 Epoch 增量快取機制。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `UnionFindEngine` | `physics.UnionFindEngine` | 主引擎，`@ThreadSafe` |
| `CachedResult` | （內部 record） | 帶 epoch 標記的快取結果 |
| `PhysicsResult` | `physics.PhysicsResult` | BFS 分析結果 |

## 核心方法

### `findUnsupportedBlocks(RWorldSnapshot, int scanMargin)`
- **參數**: 世界快照、掃描邊距（預設 4 格）
- **回傳**: `PhysicsResult`
- **說明**: 全量 BFS 分析。邊界非空氣方塊視為 Anchor，內部不連通方塊判定為懸空。

### `findUnsupportedBlocksCached(RWorldSnapshot, int scanMargin)`
- **參數**: 同上
- **回傳**: `PhysicsResult`
- **說明**: 帶 Epoch 快取的版本，epoch 未變動且區域未標髒時直接回傳快取。

### `notifyStructureChanged(BlockPos)`
- **參數**: 變動的方塊位置
- **說明**: 遞增全域 epoch，標記受影響的 chunk 為 dirty（含周圍 8 個 chunk）。

### `evictStaleEntries()`
- **回傳**: `int`（驅逐條目數）
- **說明**: 驅逐 epoch 差距超過 64 的過期快取。建議每 200 ticks 呼叫。

## 錨定策略（v3 Scan Margin）

- 掃描區 = 使用者指定範圍 + margin（預設 4 格）
- Anchor = 掃描區邊界上的所有非空氣方塊
- 崩塌區 = 僅限內部（排除 margin）
- margin 給 BFS 額外空間追蹤支撐路徑

## 效能設計

- **零 GC**: BitSet (nonAir/supported) + int[] queue
- **1D index 運算**: 避免 BlockPos 物件分配
- **雙煞車**: bfs_max_blocks (65536) + bfs_max_ms (50ms)
- **Epoch 增量快取**: AtomicLong epoch + ConcurrentHashMap + CAS 保護

## 關聯接口

- 依賴 → [RWorldSnapshot](L3-force-solver.md)（世界快照）
- 依賴 → [BRConfig](../L2-material/L3-registry.md)（BFS 限制參數）
- 被依賴 ← [CollapseManager](../L2-collapse/L3-collapse-manager.md)
