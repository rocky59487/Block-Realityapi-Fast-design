---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFFailureRecorder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFFailureRecorder

> [!info] 摘要
> PFSF 失效偵測管線 — failure scan / sparse scatter / failure compact / phi reduce。  <p>管理 4 種 GPU dispatch：</p> <ul> <li>Failure Scan — 4 模式斷裂偵測 (cantilever, crushing, no_support, tension)</li> <li>Sparse Scatter — 增量更新（37MB → ~200 bytes）</li> <li>Failure Compact — 壓縮 readback（1MB → ~100 bytes）</li> <li>Phi Max Reduction — max φ 兩階段 GPU 歸約（N → ceil(N/512) → 1）</li> </ul>

## 🔗 Related
- [[PFSFFailureRecorder]]
- [[compact]]
- [[dispatch]]
- [[fail]]
- [[read]]
- [[tension]]
