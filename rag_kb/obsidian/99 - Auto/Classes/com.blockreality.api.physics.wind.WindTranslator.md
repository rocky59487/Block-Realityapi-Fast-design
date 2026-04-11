---
id: "java_api:com.blockreality.api.physics.wind.WindTranslator"
type: class
tags: ["java", "api", "wind"]
---

# 🧩 com.blockreality.api.physics.wind.WindTranslator

> [!info] 摘要
> 風場轉譯層 — 壓力投射步的通用求解器映射。  <p>Wind 使用分步法： <ol> <li>Advection pass（{@link WindAdvector}）：u = u - dt(u·∇)u</li> <li>Pressure projection（此轉譯器 + DiffusionSolver）：∇²p = ∇·u</li> <li>Velocity correction：u = u - ∇p</li> </ol>  <p>此轉譯器只負責步驟 2（壓力 Poisson 求解）。

> [!tip] 資訊
> 🔌 Implements: [[DomainTranslator]]

## 🔗 Related
- [[DiffusionSolver]]
- [[DomainTranslator]]
- [[WindAdvector]]
- [[WindTranslator]]
