# 物理節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

物理節點（Category C）用於配置荷載條件、求解器參數、結果視覺化及崩塌行為，共 17+ 個節點。

## 荷載 (load/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `GravityNode` | physics.load.Gravity | 重力荷載 |
| `ConcentratedLoadNode` | physics.load.ConcentratedLoad | 集中荷載 |
| `DistributedLoadNode` | physics.load.DistributedLoad | 分布荷載 |
| `WindLoadNode` | physics.load.WindLoad | 風荷載 |
| `SeismicLoadNode` | physics.load.SeismicLoad | 地震荷載 |
| `ThermalLoadNode` | physics.load.ThermalLoad | 溫度荷載 |
| `MomentCalculatorNode` | physics.load.MomentCalculator | 力矩計算 |

## 求解器 (solver/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `ForceEquilibriumNode` | physics.solver.ForceEquilibrium | SOR 力平衡迭代求解器 |
| `BeamAnalysisNode` | physics.solver.BeamAnalysis | 樑分析（Euler 挫屈） |
| `CoarseFEMNode` | physics.solver.CoarseFEM | 粗網格有限元素法 |
| `SupportPathNode` | physics.solver.SupportPath | 支撐路徑分析 |
| `SpatialPartitionNode` | physics.solver.SpatialPartition | 空間分割優化 |
| `PhysicsLODNode` | physics.solver.PhysicsLOD | 物理細節層級 |

### ForceEquilibriumNode 詳細

- **輸入**: enabled, maxIterations(10~500), convergenceThreshold(0.0001~0.1), omega(1.0~1.95), autoOmega, warmStartEntries(0~256)
- **輸出**: solverSpec(STRUCT), convergenceRate(FLOAT), iterationsUsed(INT), residual(FLOAT)
- **evaluate()**: 組建 CompoundTag 規格，供 `ForceEquilibriumSolver` 讀取

## 結果視覺化 (result/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `StressVisualizerNode` | physics.result.StressVisualizer | 應力色彩映射 |
| `DeflectionMapNode` | physics.result.DeflectionMap | 撓度分布圖 |
| `LoadPathVisualizerNode` | physics.result.LoadPathVisualizer | 荷載路徑視覺化 |
| `StructuralScoreNode` | physics.result.StructuralScore | 結構評分 |
| `UtilizationReportNode` | physics.result.UtilizationReport | 利用率報告 |

## 崩塌 (collapse/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `CollapseConfigNode` | physics.collapse.CollapseConfig | 崩塌配置 |
| `BreakPatternNode` | physics.collapse.BreakPattern | 破壞模式 |
| `FailureModeNode` | physics.collapse.FailureMode | 失效模式 |
| `CableConstraintNode` | physics.collapse.CableConstraint | 纜索約束 |

## 關聯接口
- 依賴 → API 層 `ForceEquilibriumSolver`、`BeamStressEngine`、`CollapseManager`
- 被依賴 ← [PhysicsBinder](L3-binding.md)（將物理參數推送到 `BRConfig`）
