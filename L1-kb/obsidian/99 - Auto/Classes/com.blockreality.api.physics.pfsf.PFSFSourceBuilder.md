---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFSourceBuilder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFSourceBuilder

> [!info] 摘要
> PFSF 源項建構器 — 計算力臂、拱效應修正、每體素源項。  核心演算法： <ol> <li>多源 BFS 計算水平力臂 arm_i（§2.4.1）</li> <li>雙色 BFS 計算 ArchFactor（§2.5.2）</li> <li>距離加壓源項：ρ' = ρ × [1 + α × arm × (1 - archFactor)]（§2.4 + §2.5.1）</li> </ol>

## 🔗 Related
- [[Builder]]
- [[PFSFSourceBuilder]]
