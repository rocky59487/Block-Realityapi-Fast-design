---
id: "java_api:com.blockreality.api.event.RStructureCollapseEvent"
type: class
tags: ["java", "api", "event"]
---

# 🧩 com.blockreality.api.event.RStructureCollapseEvent

> [!info] 摘要
> Block Reality 結構坍方事件。  當 LoadPathEngine 或 SupportPathAnalyzer 判定方塊群失去支撐時， 在 FORGE event bus 上 post 此事件，讓外部模組（CI、視覺效果等）可以掛接。  事件流程： 1. LoadPathEngine.onBlockBrokenCached() 觸發級聯崩塌 2. post RStructureCollapseEvent 3. CollapseManager 接管視覺效果（粒子、音效）

> [!tip] 資訊
> 🔼 Extends: Event

## 🔗 Related
- [[CollapseManager]]
- [[LoadPathEngine]]
- [[RStructure]]
- [[RStructureCollapseEvent]]
- [[SupportPathAnalyzer]]
- [[onBlockBroken]]
- [[onBlockBrokenCached]]
