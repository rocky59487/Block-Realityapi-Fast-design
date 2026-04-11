---
id: "java_api:com.blockreality.api.client.render.optimization.QualityLevel"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.QualityLevel

> [!info] 摘要
> Shader LOD 系統 — 根據效能預算動態降級 shader 品質。  技術架構： - 幀時間監控（滾動平均 60 幀） - 三級品質：HIGH / MEDIUM / LOW - 降級策略（從最不明顯的效果開始）： HIGH→MEDIUM: 關閉 POM + SSS + Anisotropic，SSGI 半取樣 MEDIUM→LOW:  關閉 SSR + Contact Shadow + SSGI，降 CSM 解析度 - 升級策略（需連續穩定 120 幀才升級，防止抖動） - 每個 composite pass 在渲染前查詢 shouldRender(passName) 決定是否跳過  參考： - Sodium: 動態 render distance 調整 - Unreal Engine: Scalability Settings - Iris: shader profile (lo

## 🔗 Related
- [[QualityLevel]]
- [[distance]]
- [[render]]
- [[shouldRender]]
