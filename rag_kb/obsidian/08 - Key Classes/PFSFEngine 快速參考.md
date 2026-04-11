---
id: "keyclass:PFSFEngine"
type: key_class
tags: ["key-class", "pfsf", "gpu", "scheduler"]
fqn: "com.blockreality.api.physics.pfsf.PFSFEngine"
thread_safety: "server-thread only"
related_test: "com.blockreality.api.physics.pfsf.PFSFSchedulerTest"
---

# 🧩 PFSFEngine 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.pfsf.PFSFEngine`
> **Thread Safety**: server-thread only

## 📋 職責
PFSF 物理引擎的 Java 端主入口，管理 Vulkan Compute 上下文、多島嶼排程、LOD 決策。不直接執行數值計算，而是協調 PFSFScheduler、PFSFDispatcher、PFSFIslandBuffer。線程安全：僅在 ServerTick 中執行。主要方法：tick(ServerLevel)、submitIsland(StructureIsland)、readResults()。修改注意：調度策略變更會影響性能與正確性，建議同時更新 PFSFSchedulerTest。

> [!tip] Metadata
> 🧪 Test: [[PFSFSchedulerTest]]

## 🔗 Related Notes
- [[IslandBuffer]]
- [[PFSFDispatcher]]
- [[PFSFEngine]]
- [[PFSFIslandBuffer]]
- [[PFSFScheduler]]
- [[PFSFSchedulerTest]]
- [[Result]]
- [[StructureIsland]]
- [[read]]
- [[submit]]
- [[tick]]
