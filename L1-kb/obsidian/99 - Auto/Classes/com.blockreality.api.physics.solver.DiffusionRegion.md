---
id: "java_api:com.blockreality.api.physics.solver.DiffusionRegion"
type: class
tags: ["java", "api", "solver"]
---

# 🧩 com.blockreality.api.physics.solver.DiffusionRegion

> [!info] 摘要
> 通用擴散區域 — 所有物理域（流體/風/熱/電磁）的共用 SoA 資料容器。  <p>儲存 ∇·(σ∇φ) = f 所需的全部陣列： <ul> <li>{@code phi[]} — 主求解變量（流體勢能/溫度/電位/壓力）</li> <li>{@code conductivity[]} — 擴散係數 σ（密度/熱擴散率/電導率）</li> <li>{@code source[]} — 源項 f（重力/熱源/電荷密度）</li> <li>{@code type[]} — 體素類型（0=空/1=活動/4=固體牆）</li> </ul>  <p>各物理域透過 {@link DomainTranslator} 將自己的物理量映射到這些陣列。

## 🔗 Related
- [[DiffusionRegion]]
- [[DomainTranslator]]
- [[main]]
- [[solve]]
- [[type]]
