# L2-material — 材料系統

> 所屬：L1-api > L2-material

## 概述

Block Reality 的材料定義與管理系統，以真實工程參數（MPa、GPa、kg/m³）建模 Minecraft 方塊的力學行為。支援預設材料列舉、自訂材料建構、動態 RC 融合計算。

## 子文件

| 文件 | 類別 | 說明 |
|------|------|------|
| [L3-registry](L3-registry.md) | BlockTypeRegistry | 動態方塊類型註冊表 |
| [L3-default-material](L3-default-material.md) | DefaultMaterial | 12 種預設材料的工程參數 |
| [L3-custom-material](L3-custom-material.md) | CustomMaterial + Builder | 自訂材料建構器 |
| [L3-dynamic-material](L3-dynamic-material.md) | DynamicMaterial | RC 融合動態材料 |

## 核心介面

`RMaterial` 是所有材料的共用介面，定義：
- `getRcomp()` — 抗壓強度 (MPa)
- `getRtens()` — 抗拉強度 (MPa)
- `getRshear()` — 抗剪強度 (MPa)
- `getDensity()` — 密度 (kg/m³)
- `getMaterialId()` — 材料識別 ID
- `getYoungsModulusPa()` — 楊氏模量 (Pa)，預設近似 max(Rcomp, Rtens) x 1e9
- `getPoissonsRatio()` — 泊松比
- `getYieldStrength()` — 降伏強度 (MPa)

## 實作關係

```
RMaterial (interface)
  ├── DefaultMaterial (enum, 12 種)
  ├── CustomMaterial (Builder pattern)
  └── DynamicMaterial (record, runtime 計算)
```
