---
id: "java_api:com.blockreality.api.client.render.effect.BRRainRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRRainRenderer

> [!info] 摘要
> GPU Instanced 雨滴渲染器。  技術架構： - 雨滴為垂直伸展的線段（billboard quad → stretch along velocity） - GPU Instanced rendering：單一 quad 幾何 + per-instance 位置/速度/生命 - 粒子池預分配，零 GC - 包含水花（splash）子系統：雨滴落地時在落點生成扇形噴濺 - 濕潤 PBR 修正：全域 wetness 影響 GBuffer roughness/reflectance  參考： - NVIDIA "Rain" demo (GPU Gems 3) - Iris/BSL rain streak particles  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRRainRenderer]]
- [[author]]
- [[quad]]
- [[render]]
- [[ring]]
