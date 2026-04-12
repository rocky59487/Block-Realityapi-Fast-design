---
id: "java_api:com.blockreality.api.client.render.effect.BRWeatherEngine"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRWeatherEngine

> [!info] 摘要
> 天氣引擎 — 統一管理所有天氣子系統的中央狀態機。  天氣類型： - CLEAR:   無降水，正常渲染 - RAIN:    雨滴 + 水花 + 濕潤 PBR（反射率提升、粗糙度降低） - SNOW:    雪花 + 積雪漸變（法線偏移覆蓋白色） - STORM:   暴雨 + 閃電 + 螢幕閃光 - AURORA:  極光帷幕（高緯度夜晚自動觸發）  設計原則： - 天氣轉場平滑過渡（intensity 0→1 線性插值） - 每種天氣子系統獨立 init/tick/render/cleanup - 濕潤度係數全域共享（GBuffer material pass 讀取） - 與大氣引擎聯動（雲量影響降水概率）  @author Block Reality Team @version 1.0 @deprecated Since 2.0, superseded by Vulkan RT +

## 🔗 Related
- [[BRWeatherEngine]]
- [[author]]
- [[cleanup]]
- [[init]]
- [[material]]
- [[render]]
- [[tick]]
