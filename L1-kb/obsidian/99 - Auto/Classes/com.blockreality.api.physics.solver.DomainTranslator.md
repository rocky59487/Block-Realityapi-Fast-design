---
id: "java_api:com.blockreality.api.physics.solver.DomainTranslator"
type: class
tags: ["java", "api", "solver"]
---

# 🧩 com.blockreality.api.physics.solver.DomainTranslator

> [!info] 摘要
> 物理域轉譯器介面 — 將域概念映射到通用擴散求解器。  <p>每個物理域（流體/風/熱/電磁）實作此介面，在求解前後進行轉譯： <ul> <li>{@link #populateRegion} — 域 → 求解器：將域物理量映射到 σ[], f[], φ[]</li> <li>{@link #interpretResults} — 求解器 → 域：從 φ[] 導出域物理量（壓力/溫度/電流）</li> </ul>  <h3>域映射表</h3> <pre> 域       | σ (conductivity)  | f (source)    | φ (phi)    | gravity ─────────┼───────────────────┼───────────────┼────────────┼──────── Fluid    | ρ-based           | ρgh 

## 🔗 Related
- [[DomainTranslator]]
- [[Result]]
- [[interpretResults]]
- [[main]]
- [[populate]]
- [[populateRegion]]
- [[solve]]
