---
id: "java_api:com.blockreality.api.physics.BeamStressEngine"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.BeamStressEngine

> [!info] 摘要
> Euler-Bernoulli 梁應力引擎 — 高精度結構分析。  靈感來源：NASA/Cornell Voxelyze 體素物理引擎。  與 SupportPathAnalyzer 的差異： SPA = BFS + 簡化力矩公式（即時近似，50ms 內完成） BSE = 梁元素模型 + 內力計算（精確但較慢，適合小型結構）  演算法流程： 1. 掃描指定區域，為每對相鄰 RBlock 建立 BeamElement 2. 從錨定點出發，沿梁元素傳遞荷載（自重 + 累積荷載） 3. 每根梁計算軸力、彎矩、剪力 4. 對比容許值，計算利用率（utilization ratio） 5. 利用率 > 1.0 的梁 = 結構失效  使用時機： - /br_stress --precise 指令 - 結構方塊數 < 500 時自動啟用 - /fd export 前的最終驗證  效能特性： - O(N×

## 🔗 Related
- [[BeamElement]]
- [[BeamStressEngine]]
- [[RBlock]]
- [[SupportPathAnalyzer]]
