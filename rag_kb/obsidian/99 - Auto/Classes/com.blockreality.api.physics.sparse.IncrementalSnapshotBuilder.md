---
id: "java_api:com.blockreality.api.physics.sparse.IncrementalSnapshotBuilder"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.IncrementalSnapshotBuilder

> [!info] 摘要
> 增量式快照建構器 — 取代舊的 SnapshotBuilder.capture() 全量掃描。  三階段設計： Phase 1: 快速索引 — 只掃描 Section 標頭，建立 SVO 骨架 利用 hasOnlyAir() 跳過空 Section 時間: O(Section 數量) ≈ < 5ms for 107K sections  Phase 2: 延遲填充 — 首次存取 Section 時才讀取 block state 物理引擎觸及某 Section → 觸發 populateSection() 可用 PhysicsExecutor 多執行緒並行填充  Phase 3: 增量同步 — 只更新 dirty sections ServerTickHandler 收集 BlockEvent → 標記 dirty sections 下次計算前只重建 dirty sections（而非全量重

## 🔗 Related
- [[Builder]]
- [[IncrementalSnapshotBuilder]]
- [[PhysicsExecutor]]
- [[ServerTickHandler]]
- [[Snapshot]]
- [[SnapshotBuilder]]
- [[capture]]
- [[populate]]
- [[populateSection]]
