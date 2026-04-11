---
id: "java_api:com.blockreality.api.physics.thermal.ThermalStressCalculator"
type: class
tags: ["java", "api", "thermal"]
---

# 🧩 com.blockreality.api.physics.thermal.ThermalStressCalculator

> [!info] 摘要
> 熱應力計算器 — 從溫度場計算結構熱應力，注入 PFSF source term。  <p>公式：σ_th = α_expansion × E × ΔT  <p>當 ΔT > {@link ThermalConstants#MIN_COUPLING_DELTA_T} 時， 熱應力轉換為 PFSF source term，可能觸發： <ul> <li>{@code THERMAL_STRESS} — 熱脹冷縮超過材料屈服強度</li> <li>{@code THERMAL_SPALLING} — 表面溫度梯度導致混凝土剝落</li> </ul>

## 🔗 Related
- [[ThermalConstants]]
- [[ThermalStressCalculator]]
