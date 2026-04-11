---
id: "java_api:com.blockreality.api.client.render.effect.BRLensFlare"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRLensFlare

> [!info] 摘要
> 程序化鏡頭光暈系統 — 太陽/強光源觸發的鏡頭折射效果。  技術融合： - John Chapman (2013): "Pseudo Lens Flare" 後處理技術 - DICE/Frostbite: "Physically Based Lens Flare" - Iris/BSL: composite lens flare pass  設計要點： - 程序化生成（無外部紋理） - 太陽遮蔽偵測（depth buffer occlusion query） - 多元素組合：光環(halo)、鬼影(ghosts)、星芒(starburst)、漫射光暈(bloom ring) - Additive 混合到主 composite buffer - 平滑淡入淡出（避免閃爍）

## 🔗 Related
- [[BRLensFlare]]
- [[bloom]]
- [[burst]]
- [[query]]
- [[render]]
- [[ring]]
- [[starburst]]
