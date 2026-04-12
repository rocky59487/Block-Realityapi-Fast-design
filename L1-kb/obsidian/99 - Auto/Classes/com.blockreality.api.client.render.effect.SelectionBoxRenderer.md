---
id: "java_api:com.blockreality.api.client.render.effect.SelectionBoxRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.SelectionBoxRenderer

> [!info] 摘要
> 增強選框渲染器 — 取代原有的簡單線框。  特效： 1. 脈衝發光邊框（sin 波週期性 alpha 變化） 2. 半透明填充面（選取區域視覺化） 3. 排除方塊標記（紅色 X 十字覆蓋） 4. 動畫過渡（選框大小改變時平滑插值）  Shader 使用： - 線框部分使用 selection_glow shader（帶脈衝 uniform） - 半透明部分使用 translucent shader - 排除標記使用 overlay shader

## 🔗 Related
- [[SelectionBox]]
- [[SelectionBoxRenderer]]
- [[alpha]]
- [[render]]
