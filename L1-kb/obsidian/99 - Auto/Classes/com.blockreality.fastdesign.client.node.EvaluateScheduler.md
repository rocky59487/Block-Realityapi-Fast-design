---
id: "java_fd:com.blockreality.fastdesign.client.node.EvaluateScheduler"
type: class
tags: ["java", "fastdesign", "node", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.EvaluateScheduler

> [!info] 摘要
> 評估排程器 — 設計報告 §2.3  惰性評估 + 髒標記傳播： 1. 修改任一輸入 → 標記該節點及所有下游為「髒」 2. 每幀只重新計算髒節點 3. 使用拓撲排序確保評估順序正確  整合到客戶端 tick 或渲染前呼叫 evaluateDirty()。

## 🔗 Related
- [[EvaluateScheduler]]
- [[evaluate]]
- [[evaluateDirty]]
- [[tick]]
