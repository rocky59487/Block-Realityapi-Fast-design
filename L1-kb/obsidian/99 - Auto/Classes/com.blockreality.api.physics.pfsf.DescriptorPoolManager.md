---
id: "java_api:com.blockreality.api.physics.pfsf.DescriptorPoolManager"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.DescriptorPoolManager

> [!info] 摘要
> On-demand Descriptor Pool 重置管理器。  <h2>設計動機</h2> 舊版固定每 20 tick 重置 descriptor pool，但： <ul> <li>空閒時（無 dirty island）仍然浪費重置呼叫</li> <li>忙碌時 20 tick 可能不夠（pool exhaustion）</li> </ul>  <h2>新策略</h2> 追蹤已分配的 descriptor set 數量，達到容量 75% 時才重置。 同時保留最大間隔作為安全保障。

## 🔗 Related
- [[DescriptorPoolManager]]
- [[tick]]
