---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFVCycleRecorder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFVCycleRecorder

> [!info] 摘要
> PFSF GPU Dispatch 錄製器 — Jacobi 迭代 + W-Cycle 多重網格。  <p>v2 升級：從 V-Cycle 升級為 W-Cycle（遞迴兩層粗網格）， 大尺度結構（>100K 體素）收斂步數減少 30-40%。</p>  <p>W-Cycle 結構：</p> <pre> Fine:   smooth → restrict ──────────────────── prolong → smooth L1:                    smooth → restrict ── prolong → smooth ↓              ↑ smooth → restrict ── prolong → smooth L2:                              direct solve (4 Jacobi) </pre>

## 🔗 Related
- [[PFSFVCycleRecorder]]
- [[smooth]]
- [[solve]]
