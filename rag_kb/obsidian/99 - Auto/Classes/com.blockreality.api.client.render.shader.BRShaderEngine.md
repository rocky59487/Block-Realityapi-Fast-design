---
id: "java_api:com.blockreality.api.client.render.shader.BRShaderEngine"
type: class
tags: ["java", "api", "shader", "client-only"]
---

# 🧩 com.blockreality.api.client.render.shader.BRShaderEngine

> [!info] 摘要
> 固化光影引擎 — 所有 Shader 在啟動時一次性編譯，不可修改。  融合技術： - Iris: 多 pass shader 架構（shadow / gbuffer / deferred / composite / final） - Radiance: PBR 材質模型 + ACES 色調映射 - Sodium/Embeddium: 頂點格式優化（壓縮 normal、material ID 打包）  Shader 清單（固化，不可外部新增）： 1. shadow        — 深度寫入（vertex-only 最小化） 2. gbuffer_terrain — 結構方塊 GBuffer 填充（PBR 材質） 3. gbuffer_entity  — 實體/骨骼動畫 GBuffer 4. translucent    — 半透明幾何（選框、幽靈方塊） 5. deferred      

## 🔗 Related
- [[BRShaderEngine]]
- [[material]]
- [[render]]
- [[shadow]]
- [[vertex]]
