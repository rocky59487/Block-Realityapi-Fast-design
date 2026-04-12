---
id: "java_api:com.blockreality.api.physics.StructureIslandRegistry"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.StructureIslandRegistry

> [!info] 摘要
> 結構島嶼登錄 — 追蹤所有 RBlock 連通分量（「島嶼」）。  設計目標： 1. 放置/破壞時 O(1)~O(K) 增量更新（K = 鄰居數 ≤ 6） 2. 支援跨 chunk 的大型結構（突破 40³ 快照上限） 3. 為並行 PhysicsExecutor 提供獨立工作單元  核心概念： - 每個 RBlock 屬於恰好一個 island - 相鄰的 RBlock 屬於同一個 island（6 方向連通） - 放置方塊時合併相鄰 island - 破壞方塊時可能分裂 island（使用 BFS 驗證）  執行緒安全： - 所有修改操作（register/unregister）應在 server thread 呼叫 - 查詢操作（getIsland/getIslandId）可從任意執行緒呼叫

## 🔗 Related
- [[PhysicsExecutor]]
- [[RBlock]]
- [[StructureIsland]]
- [[StructureIslandRegistry]]
- [[com.blockreality.api.physics.StructureIsland]]
- [[getIsland]]
- [[getIslandId]]
- [[read]]
- [[register]]
- [[unregister]]
