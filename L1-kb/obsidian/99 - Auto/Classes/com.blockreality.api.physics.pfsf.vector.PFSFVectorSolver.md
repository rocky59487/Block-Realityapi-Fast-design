---
id: "java_api:com.blockreality.api.physics.pfsf.vector.PFSFVectorSolver"
type: class
tags: ["java", "api", "vector"]
---

# 🧩 com.blockreality.api.physics.pfsf.vector.PFSFVectorSolver

> [!info] 摘要
> PFSF 混合向量場求解器 — 架構預備 stub。  <p>v2 Phase D (探索性)：在高應力巨集塊（8³）中實例化局部 3D LSM 向量求解器， 以精確計算剪力和扭轉行為。全域仍維持低成本純量場。</p>  <p>當前為 stub 實作 — 永遠回傳「純量場已足夠」。 完整實作計畫於 v2.1。</p>  <p>架構設計：</p> <ul> <li>掃描巨集塊應力比 > 0.7 → 啟動向量求解</li> <li>純量 φ 梯度作為向量場的 Neumann BC</li> <li>局部 8³×3 float = 6KB，完全放入 shared memory</li> <li>結果回饋修正純量場的 conductivity</li> </ul>

## 🔗 Related
- [[PFSFVectorSolver]]
