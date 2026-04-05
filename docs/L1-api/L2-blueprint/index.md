# L2-blueprint — 藍圖系統

> 所屬：L1-api > L2-blueprint

## 概述

Block Reality 的建築藍圖管理系統，支援藍圖的捕獲、NBT 序列化/反序列化、GZIP 檔案存取，以及從 Litematica (.litematic) 格式匯入。

## 子文件

| 文件 | 類別 | 說明 |
|------|------|------|
| [L3-blueprint-core](L3-blueprint-core.md) | Blueprint, BlueprintNBT | 藍圖資料結構與 NBT 序列化 |
| [L3-blueprint-io](L3-blueprint-io.md) | BlueprintIO, LitematicImporter | 檔案 I/O 與外部格式匯入 |

## 設計原則

- 全部使用相對座標（relX/Y/Z），支援跨世界載入
- version 欄位支援未來格式遷移
- RMaterial 以 ID 字串儲存，載入時由 DefaultMaterial/DynamicMaterial 重建
- 檔案格式：GZIP 壓縮的 NBT，副檔名 `.brblp`
- 儲存目錄：`config/blockreality/blueprints/`
