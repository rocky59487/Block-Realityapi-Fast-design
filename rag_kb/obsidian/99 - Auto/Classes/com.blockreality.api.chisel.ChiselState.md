---
id: "java_api:com.blockreality.api.chisel.ChiselState"
type: class
tags: ["java", "api", "chisel"]
---

# 🧩 com.blockreality.api.chisel.ChiselState

> [!info] 摘要
> 雕刻狀態 — 組合形狀模板與體素網格。  物理屬性分兩種策略： 1. 模板形狀（isTemplate=true）：使用預計算的精確工程截面屬性 2. 自訂雕刻（CUSTOM）：以 1×1 全塊屬性納入計算（保守近似） 僅 fillRatio 反映實際填充率（影響質量計算）  @param shape     形狀模板 @param voxelGrid 體素網格（模板形狀自動從 shape 生成，自訂形狀由玩家編輯）

## 🔗 Related
- [[CUSTOM]]
- [[ChiselState]]
- [[State]]
- [[fill]]
- [[fillRatio]]
- [[isTemplate]]
- [[shape]]
