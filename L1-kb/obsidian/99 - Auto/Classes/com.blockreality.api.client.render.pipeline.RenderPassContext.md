---
id: "java_api:com.blockreality.api.client.render.pipeline.RenderPassContext"
type: class
tags: ["java", "api", "pipeline", "client-only"]
---

# 🧩 com.blockreality.api.client.render.pipeline.RenderPassContext

> [!info] 摘要
> 單一渲染 Pass 的執行上下文。  包含該 pass 所需的所有狀態： - 當前 pass 階段 - 攝影機資訊（視角矩陣、投影矩陣） - PoseStack（用於世界座標偏移） - Framebuffer ID（該 pass 寫入的 FBO） - partial tick（用於插值） - 陰影光源方向（僅 SHADOW pass）

## 🔗 Related
- [[RenderPass]]
- [[RenderPassContext]]
- [[com.blockreality.api.client.render.pipeline.RenderPass]]
- [[line]]
- [[render]]
- [[tick]]
