---
id: "java_api:com.blockreality.api.event.ServerTickHandler"
type: class
tags: ["java", "api", "event"]
---

# 🧩 com.blockreality.api.event.ServerTickHandler

> [!info] 摘要
> Server tick 事件處理器 — v3fix §3.2 + §3.4  負責驅動每 tick 的佇列消費： - CollapseManager.processQueue()：處理分批坍方 - ConstructionZoneManager.tickCuring()：養護進度檢查  世界卸載時清空佇列，避免跨世界洩漏。

## 🔗 Related
- [[CollapseManager]]
- [[ConstructionZone]]
- [[ConstructionZoneManager]]
- [[ServerTickHandler]]
- [[processQueue]]
- [[ring]]
- [[tick]]
- [[tickCuring]]
