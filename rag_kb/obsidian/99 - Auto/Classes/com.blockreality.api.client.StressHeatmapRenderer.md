---
id: "java_api:com.blockreality.api.client.StressHeatmapRenderer"
type: class
tags: ["java", "api", "client", "client-only"]
---

# 🧩 com.blockreality.api.client.StressHeatmapRenderer

> [!info] 摘要
> 應力熱圖渲染器 — v3fix §1.8  在方塊表面疊加半透明色彩，即時顯示應力分佈： - 0.0–0.3: 藍色（安全） - 0.3–0.7: 黃色（警告） - 0.7–1.0+: 紅色（危險）  渲染管線： RenderLevelStageEvent (AFTER_TRANSLUCENT_BLOCKS) → BufferBuilder + POSITION_COLOR → GameRenderer.getPositionColorShader()  效能保護： - 32 格距離剔除 - 僅渲染應力 > 0.05 的方塊（跳過零值） - 0.001 內縮防 Z-fighting

## 🔗 Related
- [[Builder]]
- [[StressHeatmapRenderer]]
- [[getPos]]
