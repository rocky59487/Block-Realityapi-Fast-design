# SubBlockShape / VoxelGrid / ChiselState — 雕刻形狀系統

> 所屬：L1-api > L2-chisel

## 概述

子方塊形狀模板定義預設雕刻形狀的截面屬性，VoxelGrid 儲存 10x10x10 位元網格，ChiselState 組合兩者提供物理計算介面。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `SubBlockShape` | `chisel.SubBlockShape` | 預定義形狀模板枚舉（含截面屬性） |
| `VoxelGrid` | `chisel.VoxelGrid` | 10x10x10 位元網格（long[16]，不可變） |
| `ChiselState` | `chisel.ChiselState` | record，組合 shape + voxelGrid |

## SubBlockShape 形狀列表

| 形狀 | 填充率 | 截面積 A (m²) | Ix (m⁴) | 說明 |
|------|--------|-------------|---------|------|
| FULL | 1.0 | 1.0 | 0.0833 | 完整 1x1x1 方塊 |
| SLAB_BOTTOM/TOP | 0.5 | 0.5 | 0.0104 | 半磚 |
| STAIR_N/S/E/W | 0.75 | 0.75 | 0.0547 | 階梯（L 形複合截面） |
| PILLAR | 0.503 | 0.5027 | 0.0201 | 圓柱（r=0.4m） |
| QUARTER_NE/NW/... | 0.25 | 0.25 | 0.0026 | 四分之一角塊 |
| CUSTOM | — | — | — | 玩家自訂（體素計算） |

## VoxelGrid 技術規格

| 項目 | 值 |
|------|-----|
| 解析度 | 10x10x10（每格 0.1m） |
| 儲存 | long[16]（1000 bits） |
| 大小 | 128 bytes（兩條 CPU cache line） |
| 索引 | index = x + 10 * (y + 10 * z) |
| 序列化 | NBT putLongArray |

## ChiselState 物理屬性策略

| 屬性 | 模板形狀 | 自訂形狀 |
|------|---------|---------|
| fillRatio | 枚舉預計算值 | 實際體素計算 |
| crossSectionArea | 精確截面積 | 1.0 m²（保守近似） |
| momentOfInertiaX/Y | 精確慣性矩 | 1/12 m⁴（全塊） |
| sectionModulusX/Y | 精確截面模數 | 1/6 m³（全塊） |

## 核心方法

### `VoxelGrid.get(int x, int y, int z)`
- **說明**: 查詢指定位置是否填充。

### `VoxelGrid.fillRatio()`
- **說明**: filledCount / 1000.0。

### `ChiselState.FULL`
- **說明**: 靜態常數，表示未雕刻的完整方塊。

## 關聯接口

- 被依賴 ← [BeamElement](../L2-physics/L3-beam-stress.md)（雕刻感知建梁）
- 被依賴 ← [SupportPathAnalyzer](../L2-physics/L3-load-path.md)（截面屬性影響容量）
- 被依賴 ← [LoadPathEngine](../L2-physics/L3-load-path.md)（有效截面積）
