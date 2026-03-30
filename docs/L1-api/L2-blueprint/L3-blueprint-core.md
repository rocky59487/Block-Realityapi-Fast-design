# Blueprint / BlueprintNBT — 藍圖核心

> 所屬：L1-api > L2-blueprint

## 概述

藍圖資料結構與 NBT 序列化/反序列化。一個 Blueprint 包含建築的完整快照：方塊列表、材料資訊、結構體分群、錨定狀態。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `Blueprint` | `blueprint.Blueprint` | 藍圖主資料結構 |
| `BlueprintBlock` | （Blueprint 內部類） | 單一方塊記錄 |
| `BlueprintStructure` | （Blueprint 內部類） | 結構體分群記錄 |
| `BlueprintNBT` | `blueprint.BlueprintNBT` | NBT 序列化/反序列化工具 |

## 核心方法

### `BlueprintNBT.write(Blueprint bp)`
- **回傳**: `CompoundTag`
- **說明**: 將藍圖序列化為 NBT。包含 version、metadata、size、blocks、structures。

### `BlueprintNBT.read(CompoundTag tag)`
- **回傳**: `Blueprint`
- **說明**: 從 NBT 反序列化藍圖。支援動態材料（isDynamic 標記）的還原。

## BlueprintBlock 欄位

| 欄位 | 型別 | 說明 |
|------|------|------|
| relX/Y/Z | int | 相對座標 |
| blockState | BlockState | Minecraft 方塊狀態 |
| rMaterialId | String | 材料 ID |
| structureId | int | 所屬結構體 ID |
| anchored | boolean | 是否錨定 |
| stressLevel | float | 應力等級 |
| isDynamic | boolean | 是否為動態材料 |
| dynRcomp/Rtens/Rshear/Density | double | 動態材料參數 |

## Blueprint 元數據

| 欄位 | 說明 |
|------|------|
| name | 藍圖名稱 |
| author | 作者 |
| timestamp | 建立時間 |
| version | 格式版本（目前 1） |
| sizeX/Y/Z | 藍圖尺寸 |

## 關聯接口

- 依賴 → [DefaultMaterial](../L2-material/L3-default-material.md)（材料還原）
- 被依賴 ← [BlueprintIO](L3-blueprint-io.md)（檔案 I/O）
