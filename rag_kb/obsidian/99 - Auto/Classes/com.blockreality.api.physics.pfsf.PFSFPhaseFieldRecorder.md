---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFPhaseFieldRecorder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFPhaseFieldRecorder

> [!info] 摘要
> PFSF Phase-Field 漸進式裂縫 — GPU Dispatch 錄製器。  <p>v2 Phase C：Miehe 2010 Operator Split。 在 phi solve 之後、failure scan 之前執行損傷場演化。</p>  <p>每 tick 的 operator split 序列：</p> <ol> <li>Phi solve（RBGS，用退化 σ×(1-d)²）</li> <li><b>Damage evolution（本類別）</b></li> <li>Failure scan（d > 0.95 觸發崩塌）</li> </ol>

## 🔗 Related
- [[PFSFPhaseFieldRecorder]]
- [[fail]]
- [[solve]]
- [[tick]]
