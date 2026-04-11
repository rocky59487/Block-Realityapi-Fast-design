---
id: "keyclass:PFSFDataBuilder"
type: key_class
tags: ["key-class", "pfsf", "gpu", "critical"]
fqn: "com.blockreality.api.physics.pfsf.PFSFDataBuilder"
thread_safety: "server-thread only"
related_test: "com.blockreality.api.physics.pfsf.PFSFDataBuilderTest"
---

# 🧩 PFSFDataBuilder 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.pfsf.PFSFDataBuilder`
> **Thread Safety**: server-thread only

## 📋 職責
將 Minecraft 方塊狀態轉換為 GPU 可接受的 PFSF 輸入 buffer（conductivity、source、maxPhi、rcomp、rtens）。核心約定：所有 threshold 數值上傳前必須除以 sigmaMax。線程安全：在 ServerTick 單執行緒環境中呼叫，但內部計算大量數值，避免持有大鎖。主要方法：updateSourceAndConductivity(PFSFIslandBuffer, StructureIsland, ServerLevel)。修改注意：新增任何新 buffer 時務必同步做 /= sigmaMax，否則會導致 GPU 數值尺度錯誤。

> [!warning] WARNING
> 新增 threshold buffer 必須除以 sigmaMax

> [!tip] Metadata
> 🧪 Test: [[PFSFDataBuilderTest]]
> ⚠️ **WARNING**: 新增 threshold buffer 必須除以 sigmaMax

## 🔗 Related Notes
- [[Builder]]
- [[IslandBuffer]]
- [[PFSFDataBuilder]]
- [[PFSFIslandBuffer]]
- [[StructureIsland]]
- [[rcomp]]
- [[rtens]]
- [[sigma]]
- [[update]]
- [[updateSourceAndConductivity]]
