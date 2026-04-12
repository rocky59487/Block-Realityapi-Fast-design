---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFResultProcessor"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFResultProcessor

> [!info] 摘要
> PFSF 結果處理器 — 從 PFSFEngine 提取的 GPU 結果讀回 + 失效處理 + 封包同步。  <p>P2 重構：PFSFEngine 拆分為三層之一。</p>  <p>職責： <ul> <li>processCompletedFrame() — 讀取壓縮 failure 結果 → CollapseManager</li> <li>checkDivergence() — 委託 PFSFScheduler 發散/振盪偵測</li> <li>syncStressToClients() — 每 N tick 同步應力場到附近客戶端</li> </ul>

## 🔗 Related
- [[CollapseManager]]
- [[PFSFEngine]]
- [[PFSFResultProcessor]]
- [[PFSFScheduler]]
- [[Result]]
- [[check]]
- [[checkDivergence]]
- [[fail]]
- [[processCompletedFrame]]
- [[syncStressToClients]]
- [[tick]]
