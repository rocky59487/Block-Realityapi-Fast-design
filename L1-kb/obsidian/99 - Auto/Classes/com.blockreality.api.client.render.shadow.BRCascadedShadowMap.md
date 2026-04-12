---
id: "java_api:com.blockreality.api.client.render.shadow.BRCascadedShadowMap"
type: class
tags: ["java", "api", "shadow", "client-only"]
---

# 🧩 com.blockreality.api.client.render.shadow.BRCascadedShadowMap

> [!info] 摘要
> Cascaded Shadow Maps（CSM）引擎 — 4 級聯陰影 + PCSS 軟陰影。  技術融合： - Iris/OptiFine: 多層 shadow pass，近距離高精度、遠距離低精度 - PCSS (Percentage Closer Soft Shadows): 基於遮擋距離的可變模糊半徑 - GPU Gems 3: "Parallel-Split Shadow Maps" 實用分割策略  設計要點： - 4 級聯（cascade），對數/均勻混合分割 - 每級獨立解析度（近 2048, 中 1536, 遠 1024, 最遠 512） - 穩定化：texel snapping 消除游泳邊緣 - Depth bias 自適應（斜率 + 常數 bias per cascade） - 單一 GL_TEXTURE_2D_ARRAY 儲存 4 層深度  取代 BRRenderP

## 🔗 Related
- [[BRCascadedShadowMap]]
- [[render]]
- [[shadow]]
