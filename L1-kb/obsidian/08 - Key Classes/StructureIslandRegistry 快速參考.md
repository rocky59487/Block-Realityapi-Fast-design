---
id: "keyclass:StructureIslandRegistry"
type: key_class
tags: ["key-class", "physics", "registry", "island"]
fqn: "com.blockreality.api.physics.StructureIslandRegistry"
thread_safety: "server-thread only"
related_test: "com.blockreality.api.physics.UnionFindEngineTest"
---

# 🧩 StructureIslandRegistry 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.StructureIslandRegistry`
> **Thread Safety**: server-thread only

## 📋 職責
將世界中連通的結構方塊分群為「結構島（StructureIsland）」，是 PFSF 計算的最小單位。使用 UnionFind / RegionConnectivityEngine 進行連通性分析。線程安全：ServerTick 單執行緒。主要方法：register(BlockPos)、unregister(BlockPos)、getIsland(BlockPos)、rebuildAll()。修改注意：島嶼劃分策略變更會影響 PFSF 計算的粒度與性能，建議同時更新 UnionFindEngineTest。

> [!tip] Metadata
> 🧪 Test: [[UnionFindEngineTest]]

## 🔗 Related Notes
- [[BlockPos]]
- [[RegionConnectivityEngine]]
- [[StructureIsland]]
- [[StructureIslandRegistry]]
- [[UnionFind]]
- [[UnionFindEngine]]
- [[UnionFindEngineTest]]
- [[build]]
- [[getIsland]]
- [[register]]
- [[unregister]]
