---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFScheduler"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFScheduler

> [!info] 摘要
> PFSF 迭代排程器 — Chebyshev 半迭代加速 + 保守重啟 + 發散熔斷。  <p>Chebyshev 半迭代（Wang 2015, SIGGRAPH Asia）透過動態調整每步步長 ω， 使 Jacobi 迭代收斂速度提升約一個數量級。</p>  <p>保守重啟機制在崩塌發生時重置 ω 回 1.0（純 Jacobi）， 避免拓撲突變導致 Chebyshev 過度外插。</p>  參考：PFSF 手冊 §4.3

## 🔗 Related
- [[PFSFScheduler]]
