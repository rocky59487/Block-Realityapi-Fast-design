---
id: "java_api:com.blockreality.api.physics.fluid.FluidCPUSolver"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidCPUSolver

> [!info] 摘要
> CPU 參考求解器 — PFSF-Fluid 的 CPU 回退和測試基準。  <p>提供兩種求解器： <ul> <li>{@link #solve} — Jacobi（雙緩衝，用於測試與 GPU 比對）</li> <li>{@link #rbgsSolve} — Red-Black Gauss-Seidel（原地更新，收斂速度 2×，用於遊戲 CPU 回退）</li> </ul>  <h3>Ghost Cell Neumann BC（零通量邊界）</h3> <p>對域外或固體牆方向，注入 ghost cell：H_ghost = H_current = φ + ρgh。 這保證「φ = C − ρgh（H = constant）」在整個域中（含角落）是精確不動點， 邊界處零通量 ∂H/∂n = 0 嚴格成立。  <h3>體積守恆</h3> <p>體積（volume[]）是獨立守恆量，Jac

## 🔗 Related
- [[FluidCPUSolver]]
- [[rbgsSolve]]
- [[solve]]
- [[volume]]
