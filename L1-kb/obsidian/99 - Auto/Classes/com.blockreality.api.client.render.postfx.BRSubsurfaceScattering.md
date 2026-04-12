---
id: "java_api:com.blockreality.api.client.render.postfx.BRSubsurfaceScattering"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRSubsurfaceScattering

> [!info] 摘要
> 螢幕空間次表面散射（Screen-Space Subsurface Scattering）。  技術架構： - 可分離高斯模糊近似（Separable SSS — Jimenez et al. 2015） - GBuffer material ID 標記 SSS 材質（樹葉、蜂蜜塊、冰塊、黏液塊等） - 雙 pass（水平 + 垂直）扩散光照，模擬光線穿透半透明介質 - SSS 剖面由 Burley diffusion profile 近似（Christensen-Burley 2015） - 深度感知核心：避免跨物體邊界出血  適用方塊： - 樹葉（綠色 back-scattering） - 冰塊（青色 translucency） - 蜂蜜塊（琥珀色 deep scattering） - 黏液塊（綠色 jelly scattering） - 蠟燭（暖色 wax scattering）  

## 🔗 Related
- [[BRSubsurfaceScattering]]
- [[material]]
- [[render]]
- [[ring]]
