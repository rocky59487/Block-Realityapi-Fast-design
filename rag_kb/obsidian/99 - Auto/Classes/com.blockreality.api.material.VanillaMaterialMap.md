---
id: "java_api:com.blockreality.api.material.VanillaMaterialMap"
type: class
tags: ["java", "api", "material"]
---

# 🧩 com.blockreality.api.material.VanillaMaterialMap

> [!info] 摘要
> 原版方塊 → DefaultMaterial 映射表（JSON 數據驅動）。  設計目標： 1. 將硬編碼的 if-else 材料映射改為 JSON 配置，方便 modpack 作者自訂 2. 覆蓋 100+ 原版方塊（原先僅 8 種） 3. 首次啟動自動生成預設 JSON 4. 查詢使用 ConcurrentHashMap，O(1) 無鎖讀取  檔案位置：config/blockreality/vanilla_material_map.json 格式：{ "minecraft:oak_planks": "timber", "minecraft:iron_block": "steel", ... }  映射規則： - JSON value = DefaultMaterial.getMaterialId() (全小寫) - 未列入的方塊 → fallback 到 STONE - 空氣方塊不需

## 🔗 Related
- [[DefaultMaterial]]
- [[VanillaMaterialMap]]
- [[getMaterial]]
- [[getMaterialId]]
- [[material]]
