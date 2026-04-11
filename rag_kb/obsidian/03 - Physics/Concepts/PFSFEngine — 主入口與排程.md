---
id: "pfsf:PFSFEngine"
type: pfsf
tags: ["pfsf", "engine", "scheduler", "java"]
---

# 📄 PFSFEngine — 主入口與排程

## 📖 內容
PFSFEngine 是 Java 端 PFSF 的主要入口，負責協調 ChunkPhysicsLOD、CognitiveLODManager、PFSFScheduler、PFSFDispatcher。它管理多個 PFSFEngineInstance（每個 instance 對應一個結構島或區域）。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFEngine.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFScheduler.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDispatcher.java`

## 🔗 Related Notes
- [[ChunkPhysicsLOD]]
- [[CognitiveLODManager]]
- [[PFSFDispatcher]]
- [[PFSFEngine]]
- [[PFSFEngineInstance]]
- [[PFSFScheduler]]
