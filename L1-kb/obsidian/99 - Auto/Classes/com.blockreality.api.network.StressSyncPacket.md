---
id: "java_api:com.blockreality.api.network.StressSyncPacket"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.StressSyncPacket

> [!info] 摘要
> S→C 應力同步封包 — v3fix §1.8  將伺服器端的應力計算結果批量同步到客戶端。 由 ResultApplicator 在應力寫回後觸發發送。  封包格式： [int: count] repeat count: [long: blockPos.asLong()] [float: stressLevel]

## 🔗 Related
- [[Result]]
- [[ResultApplicator]]
- [[StressSyncPacket]]
