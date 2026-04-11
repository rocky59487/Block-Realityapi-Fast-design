---
id: "java_api:com.blockreality.api.client.render.effect.BREffectRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BREffectRenderer

> [!info] 摘要
> Block Reality 特效渲染器 — 管理所有視覺特效子系統。  子系統： 1. SelectionBoxRenderer — 增強選框（發光脈衝、排除標記） 2. PlacementFXRenderer — 方塊放置粒子特效 3. StructuralFXRenderer — 結構應力閃爍、崩塌碎片 4. UIOverlayRenderer — HUD 覆蓋層資訊  分離為「半透明幾何」和「覆蓋層」兩個渲染時機： - renderTranslucentGeometry(): 在 GBuffer translucent pass 中呼叫 - renderOverlays(): 在 AFTER_LEVEL overlay pass 中呼叫

## 🔗 Related
- [[BREffectRenderer]]
- [[PlacementFXRenderer]]
- [[SelectionBox]]
- [[SelectionBoxRenderer]]
- [[StructuralFXRenderer]]
- [[UIOverlayRenderer]]
- [[render]]
- [[renderOverlay]]
- [[renderOverlays]]
- [[renderTranslucent]]
- [[renderTranslucentGeometry]]
