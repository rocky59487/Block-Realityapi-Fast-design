---
id: "java_api:com.blockreality.api.client.render.postfx.BRMotionBlurEngine"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRMotionBlurEngine

> [!info] 摘要
> Per-Pixel Velocity Buffer 引擎 — 動態模糊的基礎設施。  技術融合： - Guerrilla Games: "The Rendering of Killzone 2" velocity buffer 方案 - Jimenez 2014: "Next Generation Post Processing in Call of Duty" - Iris/BSL: 運動向量 pass  設計要點： - 獨立 FBO 存放 per-pixel 運動向量（RG16F — x/y 速度） - 使用 current vs previous frame viewProj 矩陣計算 screen-space velocity - 支援相機運動 + 物件運動 - Cinematic shader 消費 velocity buffer 進行方向性模糊 - 可獨立開關，不影響其他 pa

## 🔗 Related
- [[BRMotionBlurEngine]]
- [[prev]]
- [[render]]
- [[ring]]
