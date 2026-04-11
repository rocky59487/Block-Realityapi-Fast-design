---
id: "java_api:com.blockreality.api.client.render.effect.CameraShakeManager"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.CameraShakeManager

> [!info] 摘要
> 電影級攝影機震動管理器。  <h2>設計</h2> <ul> <li>多震源疊加 — 多處同時崩塌時自然合成</li> <li>三頻率正弦波疊加 — 主頻 + 2 倍頻 + 噪聲，產生非週期性自然震動</li> <li>距離平方反比衰減 — 越遠越輕</li> <li>時間衰退 — 強度隨 tick 線性衰退歸零</li> <li>失敗類型差異 — CRUSHING 低頻強震 vs TENSION 高頻短促</li> </ul>  <h2>接入方式</h2> 在 {@code ClientSetup.onRenderLevel(RenderLevelStageEvent)} 的 {@code AFTER_SKY} stage 呼叫 {@link #applyShake(com.mojang.blaze3d.vertex.PoseStack)}。

## 🔗 Related
- [[CameraShakeManager]]
- [[ClientSetup]]
- [[apply]]
- [[applyShake]]
- [[onRenderLevel]]
- [[render]]
- [[tick]]
- [[vertex]]
