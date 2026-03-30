# BlueprintIO / LitematicImporter — 藍圖檔案 I/O

> 所屬：L1-api > L2-blueprint

## 概述

藍圖的 GZIP 檔案存取工具，以及從 Litematica mod 的 .litematic 格式匯入的轉換器。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BlueprintIO` | `blueprint.BlueprintIO` | GZIP NBT 檔案讀寫 |
| `LitematicImporter` | `blueprint.LitematicImporter` | .litematic 格式匯入器 |

## 核心方法

### `BlueprintIO.save(ServerLevel, BlockPos min, BlockPos max, String name, String author)`
- **說明**: 從世界捕獲區域、序列化為 NBT、GZIP 壓縮存檔。檔名自動消毒（移除危險字元）。

### `BlueprintIO.capture(ServerLevel, BlockPos min, BlockPos max)`
- **回傳**: `Blueprint`
- **說明**: 從世界區域捕獲藍圖（簡便版，不含名稱和作者）。

### `BlueprintIO.load(String name)`
- **回傳**: `Blueprint`
- **說明**: 從檔案載入藍圖。

### `LitematicImporter.importLitematic(Path filePath)`
- **回傳**: `Blueprint`
- **說明**: 解析 .litematic NBT（Metadata、Regions、palette、packed long array），轉換為 Blueprint 格式。

## 檔案路徑

- 儲存目錄：`FMLPaths.CONFIGDIR / blockreality / blueprints /`
- 副檔名：`.brblp`
- 檔名消毒：移除 `..`、`/\`、`<>:"|?*`，只保留英數字與 `_-`

## Litematica 匯入流程

1. 驗證 .litematic 檔案格式
2. 解析 Metadata（Name、Author、TimeCreated、EnclosingSize）
3. 遍歷 Regions，解析每個區域的 palette 和 BlockStatePalette
4. 從 packed long array 解碼方塊狀態
5. 轉換為 Blueprint.BlueprintBlock

## 關聯接口

- 依賴 → [Blueprint / BlueprintNBT](L3-blueprint-core.md)
- 依賴 → [RBlockEntity](../L2-material/L3-default-material.md)（世界捕獲時讀取材料）
