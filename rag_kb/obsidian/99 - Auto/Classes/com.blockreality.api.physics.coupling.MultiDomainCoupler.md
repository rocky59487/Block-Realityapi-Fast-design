---
id: "java_api:com.blockreality.api.physics.coupling.MultiDomainCoupler"
type: class
tags: ["java", "api", "coupling"]
---

# 🧩 com.blockreality.api.physics.coupling.MultiDomainCoupler

> [!info] 摘要
> 跨域連動協調器 — 每 tick 在所有獨立域求解完後執行。  <h3>耦合矩陣</h3> <pre> → Structure  Fluid  Wind  Thermal  EM Wind       ✓(風壓)    —      —     ✓(對流)   ✓(風力發電) Thermal    ✓(熱應力)  ✓(相變) ✓(浮力) —       ✓(熱電) EM         ✓(雷擊)    —      —     ✓(Joule)  — </pre>  <p>每個耦合方向是一個輕量函數，讀取域 A 輸出、注入域 B 輸入。 設計為 1-tick 延遲（讀上一 tick 結果，寫入下一 tick 源項）。

## 🔗 Related
- [[MultiDomainCoupler]]
- [[main]]
- [[tick]]
