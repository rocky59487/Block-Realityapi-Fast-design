---
id: "java_api:com.blockreality.api.collapse.CollapseManager"
type: class
tags: ["java", "api", "collapse"]
---

# 🧩 com.blockreality.api.collapse.CollapseManager

> [!info] 摘要
> 坍方觸發管理器 — v3fix §3.4  呼叫 SupportPathAnalyzer 判定結構穩定性， 對不穩定方塊觸發坍方（FallingBlockEntity + 粒子效果）。  效能保護： - 每 tick 最多坍方 MAX_COLLAPSE_PER_TICK 個方塊 - 超過的排入 collapseQueue，下個 tick 繼續處理 - 由 ServerTickEvent 驅動佇列消費（需在外部掛接）

## 🔗 Related
- [[CollapseManager]]
- [[SupportPathAnalyzer]]
- [[collapse]]
- [[tick]]
