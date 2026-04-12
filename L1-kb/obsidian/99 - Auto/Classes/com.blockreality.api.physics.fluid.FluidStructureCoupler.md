---
id: "java_api:com.blockreality.api.physics.fluid.FluidStructureCoupler"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidStructureCoupler

> [!info] 摘要
> 流體-結構雙向耦合協調器。  <h3>方向 A：流體壓力 → 結構失效</h3> <p>每 tick 從 {@code FluidGPUEngine} 取得邊界壓力 map， 轉換為 {@code PFSFEngine} 的 source term lookup function。 PFSF 在下一 tick 的 {@code PFSFDataBuilder.updateSourceAndConductivity()} 中自動拾取。  <h3>方向 B：結構崩塌 → 流體釋放</h3> <p>監聽 {@code FluidBarrierBreachEvent}，通知流體引擎 將崩塌的固體體素轉為 AIR。  <h3>時序（每 tick）</h3> <pre> 1. PFSF 結構求解（8ms）— 使用上一 tick 的流體壓力 2. FluidGPUEngine.tick()（4ms）— 

## 🔗 Related
- [[Builder]]
- [[FluidBarrierBreachEvent]]
- [[FluidGPUEngine]]
- [[FluidStructureCoupler]]
- [[PFSFDataBuilder]]
- [[PFSFEngine]]
- [[tick]]
- [[update]]
- [[updateSourceAndConductivity]]
