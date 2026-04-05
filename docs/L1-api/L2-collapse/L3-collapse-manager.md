# CollapseManager — 崩塌觸發管理器

> 所屬：L1-api > L2-collapse

## 概述

呼叫 SupportPathAnalyzer 進行結構分析，將失效方塊轉為 FallingBlockEntity 並觸發粒子效果。支援每 tick 限制與跨 tick 佇列消費。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `CollapseManager` | `collapse.CollapseManager` | 主管理器，`@ThreadSafe` |
| `CollapseEntry` | （內部 record） | 佇列條目：世界、位置、失效類型 |

## 核心方法

### `checkAndCollapse(ServerLevel, BlockPos center, int radius)`
- **回傳**: `int`（崩塌方塊數）
- **說明**: 以 center/radius 做 SPA 分析，觸發坍方。

### `checkAndCollapse(ServerLevel, Set<BlockPos> islandBlocks)`
- **回傳**: `int`
- **說明**: 島嶼感知版本，只分析指定方塊集合。

### `processQueue(ServerLevel)`
- **說明**: 每 tick 從佇列消費待崩塌方塊。由 ServerTickEvent 驅動。

## 效能保護

| 參數 | 值 | 說明 |
|------|-----|------|
| MAX_COLLAPSE_PER_TICK | 500 | 每 tick 最多坍方方塊數 |
| MAX_QUEUE_SIZE | 100,000 | 佇列大小上限 |

## 崩塌流程

1. 呼叫 `SupportPathAnalyzer.analyze()` 取得 `AnalysisResult`
2. 對 failures 中的每個方塊觸發 `FallingBlockEntity.fall()`
3. 超過每 tick 上限的排入 `ConcurrentLinkedDeque` 佇列
4. 發射 `RStructureCollapseEvent`（Forge 事件匯流排）
5. 附加粒子效果（BlockParticleOption）

## 執行緒安全

使用 `ConcurrentLinkedDeque` 保證 `checkAndCollapse`（事件線程）與 `processQueue`（tick 線程）的資料安全。

## 關聯接口

- 依賴 → [SupportPathAnalyzer](../L2-physics/L3-load-path.md)（結構分析）
- 依賴 → [RStructureCollapseEvent](../../)（Forge 事件）
- 被依賴 ← [BlockPhysicsEventHandler](../../)（事件入口）
