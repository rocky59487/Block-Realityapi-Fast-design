---
id: "java_api:com.blockreality.api.physics.IStructureEngine"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.IStructureEngine

> [!info] 摘要
> 結構引擎介面 — 「黑盒子」設計（v3fix AD 1.2.5）。  接受純快照，輸出結構分析結果。 不持有任何 Minecraft Level 參照 — 可在任意線程執行。  實作： - BFSConnectivityAnalyzer (現有 BFS 引擎，速度優先) - SupportPathAnalyzer (帶權重應力 BFS) 未來可換成 FEM 求解器而不改介面  @since 1.0.0

## 🔗 Related
- [[IStructureEngine]]
- [[SupportPathAnalyzer]]
