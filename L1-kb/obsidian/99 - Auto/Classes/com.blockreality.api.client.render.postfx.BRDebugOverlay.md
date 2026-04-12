---
id: "java_api:com.blockreality.api.client.render.postfx.BRDebugOverlay"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRDebugOverlay

> [!info] 摘要
> 渲染除錯覆蓋層 — F3 + B 切換顯示，即時效能指標與渲染診斷。  功能： - FPS / 幀時間圖表（120 幀滾動歷史） - GPU 記憶體使用（VBO / FBO / Texture 統計） - 渲染管線各 Pass 耗時拆解 - GBuffer 視覺化模式（Albedo / Normal / Depth / Material / Velocity） - CSM 級聯視覺化（色彩覆蓋顯示 cascade 邊界） - LOD 層級視覺化（色彩覆蓋顯示距離分層） - Wireframe 模式  設計要點： - 全部在 overlay pass 渲染（不影響主管線） - 使用 overlay shader + immediate mode 文字 - F3+B 切換總開關，F3+1~8 切換子模式

## 🔗 Related
- [[BRDebugOverlay]]
- [[Wire]]
- [[render]]
