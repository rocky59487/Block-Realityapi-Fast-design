---
id: "java_api:com.blockreality.api.physics.fluid.FluidState"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidState

> [!info] 摘要
> 每體素流體狀態 — PFSF-Fluid 的最小資料單元。  <p>此為不可變記錄，代表單一體素在某時刻的流體快照。 GPU 端使用 SoA (Structure of Arrays) 布局以利合併存取， 此 Java record 僅供 CPU 端查詢和測試使用。  <h3>欄位說明</h3> <ul> <li>{@code type} — 流體種類（AIR/WATER/SOLID_WALL 等）</li> <li>{@code volume} — 體積分率 [0, 1]，0 = 空氣，1 = 完全充滿</li> <li>{@code pressure} — 靜水壓 (Pa) = ρ·g·h</li> <li>{@code potential} — 流體勢能（Jacobi 求解的主變量）</li> </ul>

## 🔗 Related
- [[FluidState]]
- [[State]]
- [[record]]
- [[type]]
- [[volume]]
