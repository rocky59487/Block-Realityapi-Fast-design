---
id: "java_api:com.blockreality.api.physics.PhysicsExecutor"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.PhysicsExecutor

> [!info] 摘要
> 物理運算專用執行緒池。 單例管理，生命週期跟隨 Minecraft Server。  ★ Phase 2 升級：改用 ForkJoinPool 支援多島嶼並行計算。 - 不同 island 的物理任務可以並行執行 - 保留 2 個 CPU 核心給 Minecraft server thread 和 network I/O - 可透過 BRConfig.physicsThreadCount 配置（預設 auto）  向後相容：原有的 submit(snapshot, scanMargin) API 保持不變。

## 🔗 Related
- [[BRConfig]]
- [[Config]]
- [[ForkJoinPool]]
- [[PhysicsExecutor]]
- [[Thread]]
- [[read]]
- [[submit]]
