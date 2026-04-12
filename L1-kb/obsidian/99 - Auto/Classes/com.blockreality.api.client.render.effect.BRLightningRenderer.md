---
id: "java_api:com.blockreality.api.client.render.effect.BRLightningRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRLightningRenderer

> [!info] 摘要
> 程序化閃電渲染器 — 暴風雨期間的閃電效果。  技術架構： - 閃電 bolt：遞迴 L-system 分支生成（GPU 端程序化） - 全螢幕白色閃光疊加（淡入瞬間 → 指數衰減） - 環境光瞬間提升（deferred lighting 讀取 lightningFlash uniform） - 閃電位置隨機偏移（螢幕空間 UV）  參考： - "Real-time Lightning Rendering" (GDC 2012) - BSL Shader 暴風雨模組  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRLightningRenderer]]
- [[author]]
- [[lightning]]
- [[render]]
- [[ring]]
