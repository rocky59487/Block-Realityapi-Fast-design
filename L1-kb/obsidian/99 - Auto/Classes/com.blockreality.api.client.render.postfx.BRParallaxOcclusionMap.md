---
id: "java_api:com.blockreality.api.client.render.postfx.BRParallaxOcclusionMap"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRParallaxOcclusionMap

> [!info] 摘要
> 視差遮蔽映射引擎（Parallax Occlusion Mapping — POM）。  技術架構： - Screen-space POM composite pass（後處理風格，非幾何置換） - 步進式光線行進（16 步基礎 + 二分精修 4 步） - 高度圖從 GBuffer material 的 height 通道讀取 - 自遮蔽（self-shadowing）計算增加深度感 - 視角越平越多步數（自適應步進） - LOD 漸隱：距離遠時衰減 POM 效果（避免遠處 aliasing）  適用方塊： - 石磚（凹凸磚縫） - 鵝卵石（不規則凹凸） - 深板岩（層紋 parallax） - 木板（木紋凸起） - 沙子/礫石（顆粒感）  參考： - "Steep Parallax Mapping" (McGuire & McGuire 2005) - "Parallax Occlusi

## 🔗 Related
- [[BRParallaxOcclusionMap]]
- [[height]]
- [[material]]
- [[render]]
- [[shadow]]
