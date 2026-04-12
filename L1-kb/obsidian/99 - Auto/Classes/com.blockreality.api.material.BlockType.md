---
id: "java_api:com.blockreality.api.material.BlockType"
type: class
tags: ["java", "api", "material"]
---

# 🧩 com.blockreality.api.material.BlockType

> [!info] 摘要
> Block Reality 方塊類型（核心 enum）。  類型決定方塊在結構中的角色： PLAIN        — 素材 (原版方塊預設)           factor=1.0 REBAR        — 鋼筋 (提供抗拉)               factor=1.2 CONCRETE     — 混凝土 (提供抗壓)             factor=0.8 RC_NODE      — RC 融合節點 (鋼筋+混凝土)     factor=0.7 ANCHOR_PILE  — 錨定樁 (手動放置的地基錨定點)  factor=0.5  structuralFactor 是 SPHStressEngine 和 BlockTypeRegistry 共用的 唯一數據來源（Single Source of Truth），避免兩處維護不同步。  RC_NODE 由 RCFu

## 🔗 Related
- [[ANCHOR_PILE]]
- [[BlockType]]
- [[BlockTypeRegistry]]
- [[SPHStressEngine]]
- [[Type]]
- [[material]]
