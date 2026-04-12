---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFDispatcher"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFDispatcher

> [!info] 摘要
> PFSF GPU Dispatch 錄製器 — 從 PFSFEngine 提取的 GPU 命令錄製邏輯。  <p>P2 重構：PFSFEngine 拆分為三層之一。</p>  <p>職責： <ul> <li>recordSolveSteps() — Hybrid RBGS+PCG / 純 RBGS + W-Cycle 迭代錄製</li> <li>recordPhaseFieldEvolve() — Ambati 2015 相場演化</li> <li>recordFailureDetection() — 失效掃描 + compact readback + phi reduce</li> <li>handleSparseOrFullUpload() — 稀疏/全量上傳決策</li> </ul>  <p>Hybrid RBGS+PCG 策略（pfsfUsePCG = true 時啟用）：</p>

## 🔗 Related
- [[PFSFDispatcher]]
- [[PFSFEngine]]
- [[compact]]
- [[handle]]
- [[load]]
- [[read]]
- [[record]]
- [[recordFailureDetection]]
- [[recordPhaseFieldEvolve]]
- [[recordSolveSteps]]
