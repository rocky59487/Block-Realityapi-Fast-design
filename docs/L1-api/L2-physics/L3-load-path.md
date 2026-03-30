# LoadPathEngine / SupportPathAnalyzer — 載重傳導路徑

> 所屬：L1-api > L2-physics

## 概述

載重傳導路徑引擎將建築轉化為「往下長」的支撐樹，每個方塊記住「我的重量傳給誰」。SupportPathAnalyzer 則使用帶權重的 BFS 進行懸臂力矩與壓碎判定。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `LoadPathEngine` | `physics.LoadPathEngine` | 增量式載重傳遞（放置/破壞事件驅動），`@NotThreadSafe` |
| `SupportPathAnalyzer` | `physics.SupportPathAnalyzer` | 全域帶權重應力 BFS 分析 |
| `AnalysisResult` | （SPA 內部 record） | 穩定/失效方塊集合、應力圖 |
| `FailureReason` | （SPA 內部 record） | 崩塌原因：CANTILEVER_BREAK / CRUSHING / NO_SUPPORT |

## 核心方法 — LoadPathEngine

### `onBlockPlaced(ServerLevel, BlockPos)`
- **回傳**: `boolean`（是否找到支撐）
- **說明**: 找最佳支撐者、設定 parent、沿樹向下傳遞自重。O(H) 複雜度。

### `onBlockBroken(ServerLevel, BlockPos)`
- **回傳**: `int`（崩塌方塊數）
- **說明**: 移除載重、收集孤兒、嘗試重新連接，失敗則級聯崩塌。超過 64 個 FallingBlockEntity 改為 ItemEntity。

### `findBestSupport(ServerLevel, BlockPos, RBlockEntity)`
- **回傳**: `BlockPos`（最佳支撐者，null=無）
- **說明**: 優先順序 DOWN > 側向 > UP。評分考慮材料強度、錨定加分、載重利用率折扣。

### `propagateLoadDown(ServerLevel, BlockPos, float delta)`
- **說明**: 沿支撐樹傳遞載重變化，更新應力視覺化，觸發 StressUpdateEvent。

## 核心方法 — SupportPathAnalyzer

### `analyze(ServerLevel, BlockPos center, int radius)`
- **回傳**: `AnalysisResult`
- **說明**: 帶權重的 BFS 分析。三大判定：懸臂力矩（M > Rtens x W）、壓碎（F > Rcomp x A）、跨距超限。

### `analyze(ServerLevel, Set<BlockPos> islandBlocks)`
- **說明**: 島嶼感知版本，只分析指定方塊集合，避免誤觸無關結構。

## 力學規則

| 判定 | 公式 | 說明 |
|------|------|------|
| 懸臂斷裂 | M = Sigma(w_i x g x d_i) > Rtens x W | 逐塊累加力矩 |
| 壓碎 | F = mass x g > Rcomp x 1e6 x A | 力與容量比較 |
| 跨距超限 | armLength > maxSpan | 快速拒絕 |
| RC 融合 | R_RC_tens = R_concrete + R_rebar x phi | 鋼筋+混凝土加成 |

## 關聯接口

- 依賴 → [RMaterial](../L2-material/L3-default-material.md)、[ChiselState](../L2-chisel/L3-voxel-shape.md)
- 依賴 → [AnchorContinuityChecker](L3-connectivity.md)（錨定判定）
- 被依賴 ← [CollapseManager](../L2-collapse/L3-collapse-manager.md)
- 被依賴 ← [BlockPhysicsEventHandler](../../)（事件入口）
