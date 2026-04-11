---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFMultigridBuffers"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFMultigridBuffers

> [!info] 摘要
> PFSF 多網格粗網格 Buffer — 從 PFSFIslandBuffer 提取。  <p>管理 L1（2× 降採樣）和 L2（4× 降採樣）的 GPU buffer。 W-Cycle 多網格需要 L0→L1→L2 三層；V-Cycle 只用 L0→L1。</p>  <p>P1 重構：原本 10 個 buffer handle + 6 個維度欄位散在 PFSFIslandBuffer， 現集中到此類。</p>

## 🔗 Related
- [[IslandBuffer]]
- [[PFSFIslandBuffer]]
- [[PFSFMultigridBuffers]]
- [[handle]]
