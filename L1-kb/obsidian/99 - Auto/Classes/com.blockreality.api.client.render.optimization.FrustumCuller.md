---
id: "java_api:com.blockreality.api.client.render.optimization.FrustumCuller"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.FrustumCuller

> [!info] 摘要
> 視錐剔除器 — Sodium 風格 Frustum Culling。  技術： - 使用 JOML FrustumIntersection（提取 6 平面 + 快速 AABB 測試） - 每幀更新一次 frustum matrix（投影 × 視角） - 額外膨脹邊距（BRRenderConfig.FRUSTUM_PADDING）防止邊界 pop-in  Sodium 優化啟發： - 先粗粒度（16³ section）剔除，再細粒度（island AABB）剔除 - 結構外圍方塊邊界盒預計算，避免每幀遍歷

## 🔗 Related
- [[AABB]]
- [[BRRenderConfig]]
- [[Config]]
- [[FrustumCuller]]
- [[FrustumIntersection]]
- [[render]]
