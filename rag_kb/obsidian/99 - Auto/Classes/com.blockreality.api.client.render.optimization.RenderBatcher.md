---
id: "java_api:com.blockreality.api.client.render.optimization.RenderBatcher"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.RenderBatcher

> [!info] 摘要
> Render Batcher — Sodium/Embeddium 風格 Draw Call 合併器。  核心概念： - 將多個小 mesh 的頂點數據合併到單一 VBO - 減少 glDrawArrays/glDrawElements 呼叫次數 - 支援 multi-draw（同材質一次提交多個 mesh）  Embeddium 啟發： - 頂點格式壓縮（position: 3×float, normal: 3×byte packed, color: 4×byte, matId: 1×byte） - 但為了可讀性和相容性，此版使用 10×float per vertex  使用模式： begin() → submit() × N → flush() → 回傳 draw call 數

## 🔗 Related
- [[RenderBatcher]]
- [[begin]]
- [[color]]
- [[flush]]
- [[mesh]]
- [[render]]
- [[submit]]
- [[vertex]]
