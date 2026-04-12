---
id: "java_api:com.blockreality.api.client.render.effect.BRAuroraRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRAuroraRenderer

> [!info] 摘要
> 程序化極光渲染器 — 夜間高空帷幕光效。  技術架構： - Ray March 穿越極光層（高度 128~256，薄帷幕） - FBM 噪聲場驅動帷幕形狀（3D Simplex/Perlin） - 色彩漸變：綠→青→紫（磁場高度映射） - 動態飄動：風場偏移 + 時間演化 - 亮度脈衝：正弦波 + 噪聲調制  參考： - "Real-Time Aurora Rendering" (Lawlor et al.) - Shadertoy "Aurora Borealis" by nimitz - BSL/Complementary shader aurora pass  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRAuroraRenderer]]
- [[author]]
- [[render]]
- [[ring]]
