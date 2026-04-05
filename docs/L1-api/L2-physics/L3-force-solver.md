# ForceEquilibriumSolver — 力平衡求解器

> 所屬：L1-api > L2-physics

## 概述

基於 Gustave 結構分析庫的力平衡概念，使用 Gauss-Seidel SOR（Successive Over-Relaxation）迭代鬆弛法求解每個節點的力平衡方程。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ForceEquilibriumSolver` | `physics.ForceEquilibriumSolver` | 主求解器（靜態方法，伺服器執行緒使用） |
| `ForceResult` | （內部 record） | 單節點求解結果：總力、支撐力、穩定性、利用率 |
| `ConvergenceDiagnostics` | （內部 record） | 收斂診斷：迭代次數、殘差、omega 值 |
| `NodeState` | （內部 mutable class） | 迭代期間的節點狀態，避免 record 分配造成 GC 壓力 |

## 核心方法

### `solve(Map<BlockPos, RMaterial>, Set<BlockPos> anchors, ...)`
- **參數**: 方塊材料映射、錨定點集合、雕刻狀態
- **回傳**: `Map<BlockPos, ForceResult>` + `ConvergenceDiagnostics`
- **說明**: 對結構進行 SOR 力平衡分析。自適應調整鬆弛參數 omega（1.05~1.95），支援 warm-start 快取加速增量更新。

## 演算法細節

1. **核心假設**：每個非錨點方塊滿足 ΣF = 0（牛頓第一定律）
2. **SOR 參數**：預設 ω = 1.25，收斂緩慢時自動增加，發散時減少
3. **收斂條件**：相對殘差 < 0.1%（`maxDelta / maxForce < 0.001`）
4. **最大迭代**：100 次
5. **Warm-start**：LRU 快取（64 條目），結構僅變動 1-2 塊時迭代從 ~40 降至 ~5-10

## 效能特性

- 水平方向鄰居遍歷使用 `static final int[][]` 常數，零分配
- `NodeState` 使用 mutable class 取代 record，避免每迭代 O(N) 物件分配
- LinkedHashMap(accessOrder=true) 實現真正的 LRU 快取驅逐

## 關聯接口

- 依賴 → [PhysicsConstants](L3-beam-stress.md)（重力常數）
- 依賴 → [RMaterial](../L2-material/L3-default-material.md)（材料屬性）
- 被依賴 ← [CollapseManager](../L2-collapse/L3-collapse-manager.md)
