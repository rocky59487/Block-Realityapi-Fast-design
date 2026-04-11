---
id: "java_api:com.blockreality.api.blueprint.Blueprint"
type: class
tags: ["java", "api", "blueprint"]
---

# 🧩 com.blockreality.api.blueprint.Blueprint

> [!info] 摘要
> 藍圖資料結構 — v3fix §2.3  一個 Blueprint 代表玩家儲存的一份建築藍圖。 包含：尺寸、方塊列表、Union-Find 結構體、元數據。  設計： - 全部使用相對座標（relX/Y/Z）以支援跨世界載入 - version 欄位支援未來格式遷移 - RMaterial 以 ID 字串儲存，載入時由 DefaultMaterial/DynamicMaterial 重建  @since 1.0.0

## 🔗 Related
- [[Blueprint]]
- [[DefaultMaterial]]
- [[DynamicMaterial]]
- [[RMaterial]]
- [[blue]]
