---
id: "java_api:com.blockreality.api.physics.fluid.FluidPressureCoupler"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidPressureCoupler

> [!info] 摘要
> 流體壓力耦合器 — 將流體邊界壓力轉換為 PFSF 結構引擎的 source term。  <p>掃描所有流體區域的固體邊界，找出流體與結構接觸的面， 將靜水壓轉換為作用力並提供給 {@code PFSFDataBuilder} 注入 source term。  <h3>耦合方程</h3> <pre> 對每個固體牆面 i，若相鄰流體體素 j 的壓力 P_j： F_i = Σ_j (P_j × BLOCK_FACE_AREA × PRESSURE_COUPLING_FACTOR) 此力作為 PFSF 的額外 source term 注入。 </pre>  <p>耦合為單向（流體→結構），結構對流體的影響透過 {@code FluidBarrierBreachEvent} 事件處理。

## 🔗 Related
- [[Builder]]
- [[FluidBarrierBreachEvent]]
- [[FluidPressureCoupler]]
- [[PFSFDataBuilder]]
