---
id: "java_api:com.blockreality.api.physics.fluid.FluidConstants"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidConstants

> [!info] 摘要
> 流體物理常數 — PFSF-Fluid 全域統一。  <p>所有常數使用國際單位制（SI），與 Block Reality 結構引擎一致。 GPU compute shader 中的 push constants 從此處取值。  <h3>核心方程</h3> <pre> 總勢能: φ_total = φ_fluid + ρ·g·h 擴散:   φ_new(i) = Σ_j(outflow_ij · φ_j) / Σ_j(outflow_ij) 靜水壓: P = ρ · g · h_fluid </pre>

## 🔗 Related
- [[FluidConstants]]
- [[compute]]
- [[push]]
