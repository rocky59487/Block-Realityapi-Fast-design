# LRFD 荷載組合與 3D 力向量

> 所屬：L1-api > L2-physics

## 概述

ASCE 7-22 §2.3.1 LRFD 荷載組合系統，搭配 3D 力/力矩向量（6 DOF），
為結構分析提供完整的多荷載類型包絡搜索能力。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `LoadCombination` | `com.blockreality.api.physics` | LRFD 荷載組合列舉（7 個標準組合） |
| `LoadType` | `com.blockreality.api.physics` | 荷載類型分類（Dead/Live/Wind/Seismic/Snow/Thermal） |
| `ForceVector3D` | `com.blockreality.api.physics` | 不可變 3D 力/力矩向量（Fx,Fy,Fz,Mx,My,Mz） |

## ASCE 7-22 LRFD 組合

| 組合 | 公式 | 主要用途 |
|------|------|---------|
| LC1 | 1.4D | 純自重（施工階段） |
| LC2 | 1.2D + 1.6L + 0.5S | 主導活荷載（最常控制） |
| LC3 | 1.2D + 1.6S + 0.5L | 主導雪荷載 |
| LC4 | 1.2D + 1.0W + 0.5L + 0.5S | 風荷載組合 |
| LC5 | 0.9D + 1.0W | 上揚/傾覆檢查 |
| LC6 | 1.2D + 1.0E + 0.5L | 地震荷載組合 |
| LC7 | 0.9D + 1.0E | 地震傾覆檢查 |

## ForceVector3D — 座標系

採用 Minecraft 右手座標系：
- X — 東(+) / 西(-)
- Y — 上(+) / 下(-) — 重力方向 = -Y
- Z — 南(+) / 北(-)

六個自由度：Fx, Fy, Fz (N) + Mx, My, Mz (N·m)

## 核心方法

### `LoadCombination.combine(Map<LoadType, Double>)`
- **說明**：計算組合設計荷載值 U = Σ(γᵢ × Qᵢ)
- **參數**：各荷載類型的特徵值
- **回傳**：組合後的設計荷載值 (N)

### `LoadCombination.criticalCombinedLoad(Map<LoadType, Double>)`
- **說明**：搜索所有 7 個組合，返回最大設計荷載值
- **回傳**：最大組合荷載值 (N)

### `LoadCombination.findCriticalCombination3D(Map<LoadType, ForceVector3D>)`
- **說明**：3D 向量包絡搜索，返回最大合力的控制組合
- **回傳**：`CriticalResult3D(combination, designForce, magnitude)`

### `ForceVector3D.momentAbout(armX, armY, armZ)`
- **說明**：計算力在力臂下的力矩 M = r × F（向量外積）
- **回傳**：力矩向量

### `ForceVector3D.overturningMoment()`
- **說明**：傾覆力矩 = sqrt(Mx² + Mz²)
- **用途**：穩定性檢查

## 使用範例

```java
// 標量荷載組合
Map<LoadType, Double> loads = Map.of(
    LoadType.DEAD, 100.0,
    LoadType.LIVE, 50.0,
    LoadType.WIND, 30.0
);
LoadCombination.CriticalResult result = LoadCombination.findCriticalCombination(loads);
// result.combination() = LC2, result.designLoad() = 200.0 N

// 3D 荷載組合
Map<LoadType, ForceVector3D> loads3D = new EnumMap<>(LoadType.class);
loads3D.put(LoadType.DEAD, ForceVector3D.gravity(100.0));
loads3D.put(LoadType.WIND, ForceVector3D.horizontal(1, 0, 30.0));
LoadCombination.CriticalResult3D result3D = LoadCombination.findCriticalCombination3D(loads3D);
```

## 關聯接口

- 被依賴 ← [力平衡求解器](L3-force-solver.md) — `solveWithLateralLoads()`
- 被依賴 ← [梁應力引擎](L3-beam-stress.md) — 梁利用率計算
- 依賴 → [物理常數](L3-force-solver.md) — `PhysicsConstants`

## 參考文獻

- ASCE/SEI 7-22 — Minimum Design Loads for Buildings, §2.3.1
- EN 1990:2002 — Eurocode: Basis of structural design, §6.4.3.2
- AISC 360-22 — Specification for Structural Steel Buildings
