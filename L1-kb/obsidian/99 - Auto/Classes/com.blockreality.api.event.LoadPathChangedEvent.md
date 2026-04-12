---
id: "java_api:com.blockreality.api.event.LoadPathChangedEvent"
type: class
tags: ["java", "api", "event"]
---

# 🧩 com.blockreality.api.event.LoadPathChangedEvent

> [!info] 摘要
> Load Path Changed Event — Fired when a block's load changes due to propagation.  This event is posted on the FORGE event bus whenever a RBlockEntity's load is updated during propagateLoadDown() in LoadPathEngine.  Modules can listen to this event to monitor structural load distribution, update stress visualizations, or trigger structural analysis updates.  Module use case: Fast Design can listen t

> [!tip] 資訊
> 🔼 Extends: Event

## 🔗 Related
- [[LoadPathChangedEvent]]
- [[LoadPathEngine]]
- [[RBlock]]
- [[RBlockEntity]]
- [[load]]
- [[propagateLoadDown]]
- [[ring]]
- [[update]]
