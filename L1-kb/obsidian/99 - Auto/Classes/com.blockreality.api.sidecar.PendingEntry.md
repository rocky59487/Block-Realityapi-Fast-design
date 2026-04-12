---
id: "java_api:com.blockreality.api.sidecar.PendingEntry"
type: class
tags: ["java", "api", "sidecar"]
---

# 🧩 com.blockreality.api.sidecar.PendingEntry

> [!info] 摘要
> RPC id 計數器。 ★ Round 5 fix: 使用正數模運算防止 Integer.MAX_VALUE 溢位後產生負 ID， JSON-RPC 2.0 spec 建議 id 為正整數。getAndUpdate 保證原子性。

## 🔗 Related
- [[Entry]]
- [[PendingEntry]]
