---
id: "java_api:com.blockreality.api.client.render.postfx.BRGlobalIllumination"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRGlobalIllumination

> [!info] 摘要
> Screen-Space Global Illumination（SSGI）— 螢幕空間全域光照近似。  技術融合： - Crassin 2012: "Interactive Indirect Illumination Using Voxel Cone Tracing"（簡化版） - Jimenez 2016: "Practical Real-Time Strategies for Accurate Indirect Occlusion" (GTAO) - Iris/BSL/Complementary: SSGI composite pass  設計要點： - 半解析度運算（效能友好） - 從 GBuffer albedo + normal + depth 取樣 - 射線 march 從每個像素向半球方向取樣間接光 - Temporal accumulation 減少噪聲 - 可獨立啟用/

## 🔗 Related
- [[BRGlobalIllumination]]
- [[render]]
