---
id: "java_api:com.blockreality.api.collapse.CollapseJournal"
type: class
tags: ["java", "api", "collapse"]
---

# 🧩 com.blockreality.api.collapse.CollapseJournal

> [!info] 摘要
> 崩塌日誌 — 記錄結構崩塌事件鏈，支援因果分析與可逆計算。  <p>設計目的：</p> <ul> <li><b>因果追蹤</b>：每個崩塌事件記錄觸發者（parent），形成因果樹</li> <li><b>可逆計算</b>：記錄崩塌前的 BlockState 快照，支援 undo 回滾</li> <li><b>統計分析</b>：按 FailureType 統計崩塌頻率，回饋給求解器調優</li> </ul>  <p>生命週期：每個 ServerLevel 維護一個 journal 實例。 透過 {@link #record} 記錄，{@link #undo} 回滾最近的崩塌鏈。 超過 {@link #MAX_ENTRIES} 的舊條目自動淘汰。</p>  <p>執行緒安全：內部使用 ConcurrentLinkedDeque，可從任何執行緒記錄。</p>

## 🔗 Related
- [[CollapseJournal]]
- [[FailureType]]
- [[State]]
- [[Type]]
- [[collapse]]
- [[record]]
- [[undo]]
