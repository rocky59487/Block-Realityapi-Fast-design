---
id: "spi:IMaterialRegistry"
type: spi
tags: ["spi", "material", "registry"]
---

# 🔌 IMaterialRegistry — 材料中央註冊表

## 📝 說明
材料的中央註冊與查詢接口。預設實作為內建 DefaultMaterialRegistry。所有 CustomMaterial、DefaultMaterial 都通過此註冊表統一管理。

> [!info] Interface
> `com.blockreality.api.spi.IMaterialRegistry`

> [!info] 預設實作
> `com.blockreality.api.spi.DefaultMaterialRegistry (inline)`

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/IMaterialRegistry.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/material/BlockTypeRegistry.java`

## 🔗 Related Notes
- [[BlockTypeRegistry]]
- [[CustomMaterial]]
- [[DefaultMaterial]]
- [[DefaultMaterialRegistry]]
- [[IMaterialRegistry]]
