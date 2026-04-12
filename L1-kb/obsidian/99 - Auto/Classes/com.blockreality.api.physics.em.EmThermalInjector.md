---
id: "java_api:com.blockreality.api.physics.em.EmThermalInjector"
type: class
tags: ["java", "api", "em"]
---

# 🧩 com.blockreality.api.physics.em.EmThermalInjector

> [!info] 摘要
> Joule heat → PFSF source term bridge.  <p>Accumulates Joule heat power density P = J²/σ (W/m³) from the EM engine and makes it available to PFSF as an additional thermal source term.  <p>Usage: Each EM tick calls {@link #inject(BlockPos, float)} for hot spots. Before the next PFSF data build, call {@link #drainPendingInjections()} to retrieve and reset the accumulated map.  <p>Thread safety: Concu

## 🔗 Related
- [[BlockPos]]
- [[EmThermalInjector]]
- [[Thread]]
- [[build]]
- [[density]]
- [[drainPendingInjections]]
- [[from]]
- [[inject]]
- [[next]]
- [[read]]
- [[reset]]
- [[safe]]
- [[tick]]
