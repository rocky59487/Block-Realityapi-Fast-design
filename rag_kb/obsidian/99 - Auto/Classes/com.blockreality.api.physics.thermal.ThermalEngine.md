---
id: "java_api:com.blockreality.api.physics.thermal.ThermalEngine"
type: class
tags: ["java", "api", "thermal"]
---

# 🧩 com.blockreality.api.physics.thermal.ThermalEngine

> [!info] 摘要
> 熱傳導引擎 — 薄包裝層，委託 DiffusionSolver 進行實際計算。  <p>這是「共用求解器 + 轉譯層」架構的第一個域實作。 引擎本身不包含任何求解邏輯，全部委託給 {@link DiffusionSolver}（CPU）或通用 GPU shader。  <h3>Tick 流程</h3> <pre> 1. ThermalTranslator.populateRegion() — 從世界讀取熱源/材料屬性 2. DiffusionSolver.rbgsSolve() — 通用 RBGS 擴散迭代 3. ThermalTranslator.interpretResults() — 導出溫度場 4. ThermalStressCalculator.extractThermalStresses() — 計算熱應力 </pre>

> [!tip] 資訊
> 🔌 Implements: [[IThermalManager]]

## 🔗 Related
- [[DiffusionSolver]]
- [[IThermalManager]]
- [[Result]]
- [[ThermalEngine]]
- [[ThermalStressCalculator]]
- [[ThermalTranslator]]
- [[extract]]
- [[extractThermalStresses]]
- [[interpretResults]]
- [[populate]]
- [[populateRegion]]
- [[rbgsSolve]]
