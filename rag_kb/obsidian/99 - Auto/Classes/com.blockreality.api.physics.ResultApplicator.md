---
id: "java_api:com.blockreality.api.physics.ResultApplicator"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.ResultApplicator

> [!info] 摘要
> 結果寫回器 (Result Applicator)  負責將各引擎的計算結果（StructureResult、FusionResult、AnchorResult、StressField） 寫回到世界中的 RBlockEntity。  設計要點： 1. 主執行緒安全 — 所有 BE 操作必須在主執行緒 (server.execute) 2. 批量更新 — 使用 setStressLevelBatch() + flushSync() 降低同步開銷 3. 重試邏輯 — 最多 3 次重試 × 100ms 間隔（BE 可能延遲載入） 4. 失敗追蹤 — 記錄失敗位置，供跨 tick 恢復使用  v3fix 合規： - AD-1: 透過 BlockEntity 寫回，不用 Capability - 50ms 同步節流由 RBlockEntity 自行處理

## 🔗 Related
- [[AnchorResult]]
- [[FusionResult]]
- [[RBlock]]
- [[RBlockEntity]]
- [[Result]]
- [[ResultApplicator]]
- [[StressField]]
- [[StructureResult]]
- [[com.blockreality.api.physics.Result]]
- [[execute]]
- [[flush]]
- [[flushSync]]
- [[setStressLevel]]
- [[setStressLevelBatch]]
- [[tick]]
