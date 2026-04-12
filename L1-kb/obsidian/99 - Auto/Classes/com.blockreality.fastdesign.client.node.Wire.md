---
id: "java_fd:com.blockreality.fastdesign.client.node.Wire"
type: class
tags: ["java", "fastdesign", "node", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.Wire

> [!info] 摘要
> 連線 — 設計報告 §3.3  從 OutputPort 到 InputPort 的有向資料連線。  規則： - 連線前必須通過 TypeChecker 驗證 - 一個 InputPort 最多一條連線（後連覆蓋先連） - 一個 OutputPort 可多條連線（fan-out） - 禁止形成環路（由 NodeGraph 檢查）

## 🔗 Related
- [[InputPort]]
- [[NodeGraph]]
- [[OutputPort]]
- [[Type]]
- [[TypeChecker]]
- [[Wire]]
