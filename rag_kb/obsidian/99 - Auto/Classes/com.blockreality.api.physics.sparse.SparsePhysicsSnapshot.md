---
id: "java_api:com.blockreality.api.physics.sparse.SparsePhysicsSnapshot"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.SparsePhysicsSnapshot

> [!info] 摘要
> SVO 到現有物理引擎的橋接層。  提供與 RWorldSnapshot 完全相容的 API，讓 BFSConnectivityAnalyzer、 BeamStressEngine、SupportPathAnalyzer 等現有引擎無需修改即可使用 SVO 資料。  設計策略： - 小型查詢（< maxSnapshotBlocks）：萃取 SVO 子區域為 RWorldSnapshot（零改動路徑） - 大型查詢（> maxSnapshotBlocks）：直接操作 SVO，走新的 Section-based 引擎  這是 v2.0 → v3.0 過渡期的關鍵膠水層。  @since v3.0 Phase 1

## 🔗 Related
- [[BeamStressEngine]]
- [[RWorldSnapshot]]
- [[Snapshot]]
- [[SparsePhysicsSnapshot]]
- [[SupportPathAnalyzer]]
