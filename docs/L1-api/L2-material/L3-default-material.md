# DefaultMaterial — 預設材料列舉

> 所屬：L1-api > L2-material

## 概述

對應 Minecraft 方塊的 12 種工程材料參數列舉。數據來源：Eurocode 2 (EN 1992)、AISC Steel Manual、GB 50010/50017。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `DefaultMaterial` | `material.DefaultMaterial` | enum，實作 `RMaterial` 介面 |

## 材料參數一覽

| 材料 | Rcomp (MPa) | Rtens (MPa) | Rshear (MPa) | 密度 (kg/m³) | E (GPa) | nu |
|------|------------|------------|-------------|-------------|---------|------|
| PLAIN_CONCRETE | 25.0 | 2.5 | 3.5 | 2400 | 25 | 0.18 |
| REBAR | 250.0 | 400.0 | 150.0 | 7850 | 200 | 0.29 |
| CONCRETE (C30) | 30.0 | 3.0 | 4.0 | 2350 | 30 | 0.20 |
| RC_NODE | 33.0 | 5.9 | 5.0 | 2500 | 32 | 0.20 |
| BRICK | 10.0 | 0.5 | 1.5 | 1800 | 5 | 0.15 |
| TIMBER | 5.0 | 8.0 | 2.0 | 600 | 11 | 0.35 |
| STEEL (Q345) | 350.0 | 500.0 | 200.0 | 7850 | 200 | 0.29 |
| STONE | 30.0 | 3.0 | 4.0 | 2400 | 50 | 0.25 |
| GLASS | 100.0 | 0.5 | 1.0 | 2500 | 70 | 0.22 |
| SAND | 0.1 | 0.0 | 0.05 | 1600 | 0.01 | 0.30 |
| OBSIDIAN | 200.0 | 5.0 | 20.0 | 2600 | 70 | 0.20 |
| BEDROCK | 1e15 | 1e15 | 1e15 | 3000 | 1e6 | 0.10 |

## 核心方法

### `fromId(String id)`
- **回傳**: `DefaultMaterial`
- **說明**: 依 ID 查找，使用靜態 HashMap O(1) 查找。找不到時 fallback 為 CONCRETE。

### `getYoungsModulusPa()`
- **回傳**: `double`
- **說明**: 覆寫 RMaterial 預設方法，使用真實工程數據（GPa x 1e9 = Pa）。

## 設計備註

- BEDROCK 使用有限但極大的常數（1e15）替代 `Float.MAX_VALUE`
- SAND 的 Rtens=0 表示無抗拉能力，無法作為懸臂支撐
- TIMBER 的 Rtens(8.0) > Rcomp(5.0)，反映木材抗拉優於抗壓的真實特性

## 關聯接口

- 實作 → [RMaterial](index.md)（材料介面）
- 被依賴 ← 所有物理引擎（材料查詢）
- 被依賴 ← [VanillaMaterialMap](L3-registry.md)（原版方塊映射）
