---
id: "java_api:com.blockreality.api.physics.PhysicsScheduler"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.PhysicsScheduler

> [!info] 摘要
> 物理排程器 — 管理哪些 island 需要重新計算，並按優先度排序。  ★ Phase 7: 優先佇列排程 + 每 tick 預算控制。  優先度公式： priority = dirtyEpochDelta × blockCount / (distanceToPlayer² + 1)  高優先度：最近被修改、方塊數多、離玩家近的結構。  每 tick 預算： - 處理 island 直到累計耗時超過 40ms（留 10ms 給其他 tick 任務） - 未處理完的 island 保留到下一 tick  結果合併： - 同一 tick 內多次修改同一 island，只排程一次

## 🔗 Related
- [[PhysicsScheduler]]
- [[blockCount]]
- [[distance]]
- [[tick]]
