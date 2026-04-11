---
id: "java_api:com.blockreality.api.network.CollapseInfo"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.CollapseInfo

> [!info] 摘要
> ★ review-fix ICReM-5: S→C 崩塌效果封包  將崩塌的失敗類型同步到客戶端，使不同破壞模式有不同的視覺效果： - CANTILEVER_BREAK (0): 整段懸臂一起斷裂掉落，附帶斷裂動畫 - CRUSHING (1):         漸進式壓碎，材質逐漸裂開 - NO_SUPPORT (2):       直接掉落（無支撐）  封包格式： [int: count] repeat count: [long: blockPos.asLong()] [byte: failureType ordinal] [int: materialId]

## 🔗 Related
- [[CollapseInfo]]
- [[Type]]
- [[fail]]
- [[material]]
- [[materialId]]
- [[ordinal]]
