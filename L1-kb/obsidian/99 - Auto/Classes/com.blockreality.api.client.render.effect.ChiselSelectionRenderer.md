---
id: "java_api:com.blockreality.api.client.render.effect.ChiselSelectionRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.ChiselSelectionRenderer

> [!info] 摘要
> 鑿刀工具選取區域線框的客戶端渲染器。 當玩家使用鑿刀時，預覽哪些子體素將受到影響。  渲染器行為： - 訂閱 RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS 階段） - 僅在玩家手持 ChiselItem 時渲染 - 透過射線偵測玩家注視的方塊面 - 在該面上繪製彩色線框疊加層 - 使用脈衝透明度以提升可見性

## 🔗 Related
- [[ChiselItem]]
- [[ChiselSelectionRenderer]]
- [[render]]
