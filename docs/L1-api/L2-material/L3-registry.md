# BlockTypeRegistry — 動態方塊類型註冊表

> 所屬：L1-api > L2-material

## 概述

允許模組在不修改 BlockType 枚舉的前提下，註冊新的方塊類型（如 PRESTRESSED、COMPOSITE 等）。核心 5 種 BlockType 使用 enum，擴展類型透過此 Registry 管理。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BlockTypeRegistry` | `material.BlockTypeRegistry` | 靜態工具類，`@ThreadSafe` |
| `BlockTypeEntry` | （內部 record） | 擴展類型條目：名稱 + 結構係數 |
| `BlockType` | `material.BlockType` | 核心枚舉：PLAIN, REBAR, CONCRETE, RC_NODE, ANCHOR_PILE |

## 核心方法

### `register(String serializedName, float structuralFactor)`
- **參數**: 類型名稱（全小寫）、結構係數（1.0=PLAIN 等效）
- **回傳**: `BlockTypeEntry`
- **說明**: 註冊擴展類型，不可與核心 enum 衝突。覆蓋已有則記錄警告。

### `unregister(String serializedName)`
- **回傳**: `boolean`
- **說明**: 移除擴展類型。

### `resolveStructuralFactor(String serializedName)`
- **回傳**: `float`
- **說明**: 查詢優先順序：核心 enum → 擴展 → fallback 1.0f。

### `isCoreType(String)` / `isRegistered(String)`
- **說明**: 快速查詢是否為核心/已註冊類型。

## 內部實作

- 核心類型使用 `HashMap`（啟動時建立，不可變）
- 擴展類型使用 `ConcurrentHashMap`（支援並發讀寫）
- `clearExtensions()` 供測試使用

## 關聯接口

- 依賴 → [BlockType](L3-default-material.md)（核心枚舉）
- 被依賴 ← [SPHStressEngine](../L2-sph/L3-particle-system.md)（materialFactor 查詢）
