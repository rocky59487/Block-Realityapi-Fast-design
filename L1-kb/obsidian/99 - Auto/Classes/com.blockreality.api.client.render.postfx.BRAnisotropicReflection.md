---
id: "java_api:com.blockreality.api.client.render.postfx.BRAnisotropicReflection"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRAnisotropicReflection

> [!info] 摘要
> 各向異性反射引擎 — 金屬表面拉絲/刷紋反射。  技術架構： - GGX Anisotropic BRDF（Burley 2012 / Heitz 2014） - GBuffer tangent 向量（第 5 附件 emission 通道借用 2 分量） - 各向異性比 α_x / α_y 由 material ID 查表 - 全螢幕 composite pass，讀取 GBuffer 資料修改反射分佈  適用方塊： - 鑽石塊、鐵塊、金塊（金屬拉絲紋理） - 銅塊（氧化漸變 + 各向異性） - 紫水晶簇（結晶各向異性）  參考： - "Physically Based Shading at Disney" (Burley 2012) - "Understanding the Masking-Shadowing Function" (Heitz 2014) - labPBR materia

## 🔗 Related
- [[BRAnisotropicReflection]]
- [[material]]
- [[render]]
