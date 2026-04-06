# CustomMaterial — 自訂材料建構器

> 所屬：L1-api > L2-material

## 概述

v3fix 合規的 Builder Pattern 自訂材料，提供流暢 API 建立具完整參數驗證的不可變材料實例。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `CustomMaterial` | `com.blockreality.api.material.CustomMaterial` | 不可變自訂材料，實作 `RMaterial` |
| `CustomMaterial.Builder` | （內部類） | 鏈式建構器 |

## 核心方法

### `CustomMaterial.builder(String id)`
- **參數**: 材料識別 ID（必須）
- **回傳**: `Builder`
- **說明**: 建立 Builder 實例的工廠方法。

### `Builder.rcomp(double)` / `rtens(double)` / `rshear(double)` / `density(double)`
- **說明**: 鏈式設定參數。強度不可為負，密度必須為正。

### `Builder.build()`
- **回傳**: `CustomMaterial`
- **說明**: 驗證並建立實例。至少須有一項強度 > 0，否則拋出 `IllegalStateException`。

## 使用範例

```java
CustomMaterial mat = CustomMaterial.builder("high_strength_concrete")
    .rcomp(50)
    .rtens(4.5)
    .rshear(6.0)
    .density(2500)
    .build();
```

## 驗證規則

| 參數 | 限制 | 預設值 |
|------|------|--------|
| id | 非 null、非空 | （必填） |
| rcomp | >= 0 | 0 |
| rtens | >= 0 | 0 |
| rshear | >= 0 | 0 |
| density | > 0 | 1000 kg/m³ |
| build() | rcomp + rtens + rshear 至少一項 > 0 | — |

## 關聯接口

- 實作 → [RMaterial](index.md)
- 替代 → [DynamicMaterial.ofCustom()](L3-dynamic-material.md)（更優雅的替代方案）
