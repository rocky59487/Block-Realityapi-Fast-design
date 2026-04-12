---
id: "java_api:com.blockreality.api.physics.solver.DiffusionSolver"
type: class
tags: ["java", "api", "solver"]
---

# 🧩 com.blockreality.api.physics.solver.DiffusionSolver

> [!info] 摘要
> 通用擴散求解器 — 所有物理域共用的 Jacobi/RBGS 求解器。  <p>求解 ∇·(σ∇φ) = f，其中 σ、f、φ 的物理含義由各域的 {@link DomainTranslator} 定義。  <h3>Ghost Cell Neumann BC（零通量邊界）</h3> <p>固體牆/域外方向注入 ghost cell：H_ghost = H_current。  <h3>重力項控制</h3> <p>{@code gravityWeight=1.0} 時（流體），將 ρgh 加入總勢能； {@code gravityWeight=0.0} 時（熱/EM/風），純擴散無重力。  <p>此類從 {@code FluidCPUSolver} 提取通用邏輯，避免 4 個域各自重複。

## 🔗 Related
- [[DiffusionSolver]]
- [[DomainTranslator]]
- [[FluidCPUSolver]]
- [[main]]
- [[solve]]
