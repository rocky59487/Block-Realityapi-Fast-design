---
id: "java_api:com.blockreality.api.physics.wind.WindAdvector"
type: class
tags: ["java", "api", "wind"]
---

# 🧩 com.blockreality.api.physics.wind.WindAdvector

> [!info] 摘要
> 風場對流求解器 — Navier-Stokes 的 advection 步。  <p>使用半隱式 Lagrangian 回溯法（Stam 1999 "Stable Fluids"）： 從每個體素沿速度場反向回溯一步，用線性插值取得該位置的速度值。 保證無條件穩定（CFL 限制只影響精度，不影響穩定性）。  <p>這是風場唯一需要獨立實作的步驟。壓力投射步委託給 {@link com.blockreality.api.physics.solver.DiffusionSolver}。

## 🔗 Related
- [[DiffusionSolver]]
- [[WindAdvector]]
- [[advect]]
- [[com.blockreality.api.physics.solver.DiffusionSolver]]
- [[solve]]
