---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFIslandBuffer"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFIslandBuffer

> [!info] 摘要
> 每個 Structure Island 的 GPU 緩衝區包裝。  <p>VRAM Layout（per island, flat 3D array index = x + Lx(y + Lyz)）：</p> <pre> phi[]           float32[N]   勢能場 phi_prev[]      float32[N]   Chebyshev t-1 幀 source[]        float32[N]   自重 ρ_i conductivity[]  float32[6N]  6 向傳導率 σ_ij type[]          uint8[N]     0=air 1=solid 2=anchor fail_flags[]    uint8[N]     0=OK 1=cantilever 2=crush 3=orphan maxPhi[]        fl

## 🔗 Related
- [[IslandBuffer]]
- [[PFSFIslandBuffer]]
- [[fail]]
- [[flat]]
- [[index]]
- [[prev]]
- [[type]]
