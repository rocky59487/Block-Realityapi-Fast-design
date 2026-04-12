---
id: "java_api:com.blockreality.api.client.render.effect.BRCloudRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRCloudRenderer

> [!info] 摘要
> 程序化體積雲渲染器 — 無需外部紋理的全程序化雲層。  技術融合： - Guerrilla Games (Horizon Zero Dawn): "Real-Time Volumetric Cloudscapes" - Perlin-Worley noise 密度場 - Ray Marching + 光線散射 - Shadertoy/IQ: "Clouds" 程序化噪聲技巧 - Iris/BSL: 雲層 composite pass 整合  設計要點： - 雲層在 Y=128~256 的球殼區間 - 主 march 64 步 + 光線 march 6 步 - 密度 = FBM(Perlin) - Worley erosion - 散射：Henyey-Greenstein 雙瓣（銀邊 + 暗核） - 時間推移：UV 偏移模擬風場 - 天氣控制：coverage / density / typ

## 🔗 Related
- [[BRCloudRenderer]]
- [[density]]
- [[render]]
