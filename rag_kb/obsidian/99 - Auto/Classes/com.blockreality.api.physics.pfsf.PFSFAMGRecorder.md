---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFAMGRecorder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFAMGRecorder

> [!info] 摘要
> PFSF AMG GPU V-Cycle 錄製器。  <p>執行代數多重網格（AMG）預條件子的 GPU V-Cycle： <ol> <li>Restriction：r_c = R · r_f via {@code amg_scatter_restrict.comp.glsl}</li> <li>Coarse solve：Jacobi on coarse grid（N_coarse ≤ 512 時直接 shared memory 求解）</li> <li>Prolongation：phi_f += P · e_c via {@code amg_gather_prolong.comp.glsl}</li> </ol>  <p>整合點：{@link PFSFDispatcher#recordSolveSteps} 中，當 {@code buf.amgPreconditioner.isReady

## 🔗 Related
- [[PFSFAMGRecorder]]
- [[PFSFDispatcher]]
- [[PFSFDispatcher#recordSolveSteps]]
- [[amg_gather_prolong.comp.glsl]]
- [[amg_scatter_restrict.comp.glsl]]
- [[glsl]]
- [[isReady]]
- [[record]]
- [[recordSolveSteps]]
- [[solve]]
