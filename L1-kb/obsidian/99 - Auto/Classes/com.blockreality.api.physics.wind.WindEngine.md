---
id: "java_api:com.blockreality.api.physics.wind.WindEngine"
type: class
tags: ["java", "api", "wind"]
---

# 🧩 com.blockreality.api.physics.wind.WindEngine

> [!info] 摘要
> 風場引擎 — 混合架構：獨立 advection + 共用壓力投射。  <p>Tick 流程（Chorin 分裂法）： <ol> <li>WindAdvector.advect() — 對流步（Stam 1999 回溯法）</li> <li>WindAdvector.computeDivergence() — 計算散度 → source[]</li> <li>DiffusionSolver.rbgsSolve() — 壓力 Poisson 求解（共用！）</li> <li>WindAdvector.projectVelocity() — 速度修正 u = u - ∇p</li> </ol>

> [!tip] 資訊
> 🔌 Implements: [[IWindManager]]

## 🔗 Related
- [[DiffusionSolver]]
- [[IWindManager]]
- [[WindAdvector]]
- [[WindEngine]]
- [[advect]]
- [[compute]]
- [[computeDivergence]]
- [[projectVelocity]]
- [[rbgsSolve]]
