---
id: "java_api:com.blockreality.api.physics.BeamElement"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.BeamElement

> [!info] 摘要
> Euler-Bernoulli 梁元素 — 連接兩個相鄰體素的結構力學元素。  靈感來源：NASA/Cornell Voxelyze 開源體素物理引擎。  每個 BeamElement 建立在兩個 face-adjacent 方塊之間，攜帶： - 截面積 A (m²)：Minecraft 方塊面 = 1m × 1m = 1 m² - 截面慣性矩 I (m⁴)：正方形截面 I = b⁴/12 = 1/12 - 彈性模量 E (Pa)：由材料的 Rcomp 近似（E ≈ Rcomp × 1000 for concrete） - 梁長度 L (m)：面中心到面中心 = 1m  簡化假設（適用於 Minecraft 即時物理）： 1. 截面均為 1m × 1m 正方形（不考慮半磚、階梯） 2. 取兩端材料的較弱值（木桶原理） 3. 只考慮軸力 N 和彎矩 M（忽略扭矩，因為方塊不旋轉） 4. 不求

## 🔗 Related
- [[BeamElement]]
