---
id: "java_api:com.blockreality.api.client.render.optimization.BRGPUProfiler"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRGPUProfiler

> [!info] 摘要
> GPU Timeline Profiler — 精準量測每個渲染 pass 的 GPU 耗時。  技術架構： - GL_TIME_ELAPSED（OpenGL 3.3 Timer Query） - 雙緩衝查詢池（Frame N 提交，Frame N+1 讀回，避免 stall） - 命名區間（begin/end pass tag） - 滾動平均統計（64 幀） - 瓶頸偵測（標記最耗時的前 3 個 pass） - 整合到 BRDebugOverlay 的效能 HUD 顯示  使用方式： BRGPUProfiler.beginPass("Shadow"); ... 渲染 shadow pass ... BRGPUProfiler.endPass("Shadow");  參考： - "OpenGL SuperBible" ch.11: Timer Queries - RenderDoc / N

## 🔗 Related
- [[BRDebugOverlay]]
- [[BRGPUProfiler]]
- [[begin]]
- [[beginPass]]
- [[endPass]]
- [[line]]
- [[render]]
- [[shadow]]
