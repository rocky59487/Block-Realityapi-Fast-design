---
id: "keyclass:CollapseManager"
type: key_class
tags: ["key-class", "collapse", "physics"]
fqn: "com.blockreality.api.collapse.CollapseManager"
thread_safety: "server-thread only"
related_test: "com.blockreality.api.collapse.CollapseManagerTest"
---

# 🧩 CollapseManager 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.collapse.CollapseManager`
> **Thread Safety**: server-thread only

## 📋 職責
管理結構崩塌的生命周期，接收 PFSF 失效結果，執行連鎖崩塌分析，生成粒子效果與音效，並記錄 CollapseJournal。線程安全：ServerTick 單執行緒。主要方法：processFailures(List<BlockPos>)、triggerCollapse(StructureIsland)、getJournal()。修改注意：此處直接修改世界方塊狀態，任何額外的 Minecraft API 呼叫都可能產生 side effect，需謹慎。

> [!tip] Metadata
> 🧪 Test: [[CollapseManagerTest]]

## 🔗 Related Notes
- [[BlockPos]]
- [[CollapseJournal]]
- [[CollapseManager]]
- [[StructureIsland]]
