---
id: "java_api:com.blockreality.api.physics.ForceEquilibriumSolver"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.ForceEquilibriumSolver

> [!info] 摘要
> Gustave-Inspired Force Equilibrium Solver — Iterative relaxation-based load distribution.  靈感來源：Gustave 結構分析庫的力平衡概念。 取代傳統的 BFS-weighted 啟發式方法，改用牛頓第一定律求解每個節點的力平衡。  核心假設： 1. 每個非錨點方塊必須滿足力的平衡（Σ F = 0） 2. 重力向下施加（密度 × g），由下方/側方的支撐力承受 3. 錨點擁有無限支撐容量 4. 上方方塊的載重傳遞到下方/側方（負載分配）  算法：Gauss-Seidel 迭代鬆弛法（高效、無需矩陣求解） Max iterations: 100 Convergence threshold: 0.01 (最大力 delta < 0.01N)  @author Claude AI @version 1.0

## 🔗 Related
- [[ForceEquilibriumSolver]]
- [[author]]
- [[load]]
