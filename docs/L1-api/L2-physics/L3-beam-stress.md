# BeamStressEngine / BeamElement — 梁應力分析

> 所屬：L1-api > L2-physics

## 概述

Euler-Bernoulli 梁應力引擎，對小型結構（< 500 方塊）進行高精度結構分析。靈感來源於 NASA/Cornell Voxelyze 體素物理引擎。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BeamStressEngine` | `physics.BeamStressEngine` | 主分析引擎，支援同步與異步模式 |
| `BeamElement` | `physics.BeamElement` | 不可變 record，連接兩個相鄰體素的梁力學元素 |
| `BeamAnalysisResult` | （內部 record） | 分析結果：梁列表、利用率圖、失效梁 |

## 核心方法

### `BeamStressEngine.analyzeAsync(ServerLevel, BlockPos center, int radius)`
- **參數**: 伺服器世界、分析中心、分析半徑
- **回傳**: `CompletableFuture<BeamAnalysisResult>`
- **說明**: 異步執行梁應力分析（5 秒超時保護）。Phase 1 主線程收集快照，Phase 2 異步計算。

### `BeamStressEngine.analyze(ServerLevel, BlockPos, int)`
- **參數**: 同上
- **回傳**: `BeamAnalysisResult`
- **說明**: 同步版本，供測試指令使用。

### `BeamElement.create(BlockPos a, BlockPos b, RMaterial matA, RMaterial matB)`
- **參數**: 兩端點位置與材料
- **回傳**: `BeamElement`
- **說明**: 建立梁元素，使用調和平均計算複合剛度，材料取較弱者（木桶原理）。

### `BeamElement.utilizationRatio(double axialForce, double moment, double shear)`
- **參數**: 軸力(N)、彎矩(N·m)、剪力(N)
- **回傳**: `double`（0.0=無應力, >1.0=破壞）
- **說明**: Eurocode EN 1993-1-1 線性交互公式：(N/N_max + M/M_max) vs V/V_max。

### `BeamElement.eulerBucklingLoad()`
- **回傳**: `double`（臨界力 N）
- **說明**: P_cr = pi²EI/(KL)²，有效長度係數 K=0.7。

## 梁元素參數

| 參數 | 值 | 說明 |
|------|-----|------|
| 截面積 A | 1.0 m² | `PhysicsConstants.BLOCK_AREA` |
| 慣性矩 I | 1/12 m⁴ | 正方形截面 b⁴/12 |
| 長度 L | 1.0 m | 相鄰方塊中心距離 |
| K 係數 | 0.7 | AISC C-A-7.1，一端固定一端鉸接 |

## 演算法流程

1. 掃描區域，為相鄰 RBlock 建立 BeamElement
2. 從高到低累積荷載（重力路徑向下，無下方支撐時水平分攤）
3. 計算軸力、彎矩（自重 wL²/8 + 不平衡 |Pa-Pb|L/4）、剪力
4. 利用率 > 1.0 的梁判定為結構失效
5. BFS 從錨定點檢查孤立方塊

## 關聯接口

- 依賴 → [RMaterial](../L2-material/L3-default-material.md)（材料強度與楊氏模量）
- 依賴 → [ChiselState](../L2-chisel/L3-voxel-shape.md)（雕刻感知截面屬性）
- 被依賴 ← [StressAnalysisCommand](../../)（/br_stress --precise）
