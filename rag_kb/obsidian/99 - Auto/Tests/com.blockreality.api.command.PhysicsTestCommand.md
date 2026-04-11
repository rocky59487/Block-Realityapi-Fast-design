---
id: "java_test:com.blockreality.api.command.PhysicsTestCommand"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.command.PhysicsTestCommand

> [!info] 摘要
> /br_test_physics [size]            — 分析（含 scan margin） /br_test_physics [size] collapse   — 分析 + 崩塌  Scan Margin 機制： 使用者指定 size=32 → 引擎自動掃描 40×40×40（margin=4） BFS 在 40³ 上跑，但只有內部 32³ 的方塊會被崩塌 margin 區域捕捉外部支撐路徑，防止誤殺合理建築

## 🔗 Related
- [[PhysicsTestCommand]]
- [[collapse]]
- [[size]]
- [[test]]
