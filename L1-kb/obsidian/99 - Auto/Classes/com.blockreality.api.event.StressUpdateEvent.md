---
id: "java_api:com.blockreality.api.event.StressUpdateEvent"
type: class
tags: ["java", "api", "event"]
---

# 🧩 com.blockreality.api.event.StressUpdateEvent

> [!info] 摘要
> Stress Update Event — Fired when a block's stress level changes.  This event is posted on the FORGE event bus whenever a RBlockEntity's stress level is updated via propagateLoadDown() in LoadPathEngine.  Modules can listen to this event to trigger visual effects, update monitoring systems, or perform stress-dependent calculations.  Module use case: Construction Intern can listen for stress exceedi

> [!tip] 資訊
> 🔼 Extends: Event

## 🔗 Related
- [[LoadPathEngine]]
- [[RBlock]]
- [[RBlockEntity]]
- [[StressUpdateEvent]]
- [[propagateLoadDown]]
- [[ring]]
- [[update]]
