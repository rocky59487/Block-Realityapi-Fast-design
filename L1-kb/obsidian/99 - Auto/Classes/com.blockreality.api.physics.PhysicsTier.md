---
id: "java_api:com.blockreality.api.physics.PhysicsTier"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.PhysicsTier

> [!info] 摘要
> 物理精度層級 — 基於結構到最近玩家的距離決定分析精度。  ★ Phase 4: LOD (Level of Detail) 物理系統。 遠離玩家的結構使用低精度引擎，節省 CPU 預算。  各層級對應引擎： FULL      → PFSF GPU 物理引擎（最高精度） STANDARD  → SupportPathAnalyzer（加權 BFS，<50ms） COARSE    → LoadPathEngine（O(H) 樹走訪） DORMANT   → 不主動計算，僅使用快取結果

## 🔗 Related
- [[DORMANT]]
- [[LoadPathEngine]]
- [[PhysicsTier]]
- [[SupportPathAnalyzer]]
- [[Tier]]
