---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFConvergenceState"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFConvergenceState

> [!info] 摘要
> PFSF 收斂狀態 — 從 PFSFIslandBuffer 提取的迭代狀態。  <p>追蹤 Chebyshev 半迭代進度、頻譜半徑、φ_max 歷史（發散/振盪偵測）、 以及 GPU 端 damping 啟停。每個 island 一個實例。</p>  <p>P1 重構：原本散佈在 PFSFIslandBuffer 的 package-private 欄位， 現集中到此類以單一職責管理。</p>

## 🔗 Related
- [[IslandBuffer]]
- [[PFSFConvergenceState]]
- [[PFSFIslandBuffer]]
- [[State]]
