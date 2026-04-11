---
id: "java_api:com.blockreality.api.client.render.effect.BRParticleSystem"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRParticleSystem

> [!info] 摘要
> 高效能粒子系統 — 支援建築特效、環境粒子、UI 回饋。  設計原則： - 預分配粒子池（零 GC） - 結構化粒子陣列（SoA 風格，CPU 更新） - 單次 VBO 上傳 + instanced draw - 粒子排序可選（半透明需要，不透明不需要） - 多發射器支援（每個發射器獨立參數）  粒子類型： - 建築放置火花 - 選取框高光粒子 - 方塊破壞碎片 - 環境灰塵 / 雪花 - UI 確認特效  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRParticleSystem]]
- [[Particle]]
- [[author]]
- [[render]]
