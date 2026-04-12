---
id: "java_api:com.blockreality.api.physics.thermal.ThermalTranslator"
type: class
tags: ["java", "api", "thermal"]
---

# 🧩 com.blockreality.api.physics.thermal.ThermalTranslator

> [!info] 摘要
> 熱傳導轉譯層 — 將溫度/熱源映射到通用擴散求解器。  <h3>映射表</h3> <pre> 域概念           → 求解器概念 ────────────────────────────── 溫度 T (°C)      → φ (phi) 熱擴散率 α=k/(ρc) → σ (conductivity) 熱源 Q/(ρc)      → f (source) 固體/空氣         → type[] gravityWeight     → 0.0（無重力驅動） </pre>

> [!tip] 資訊
> 🔌 Implements: [[DomainTranslator]]

## 🔗 Related
- [[DomainTranslator]]
- [[ThermalTranslator]]
- [[type]]
