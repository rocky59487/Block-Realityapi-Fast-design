---
id: "java_api:com.blockreality.api.physics.SupportPathAnalyzer"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.SupportPathAnalyzer

> [!info] 摘要
> 支撐路徑分析器 — 帶權重的應力評估 BFS (Weighted Stress BFS)  這不是純拓撲學的「有沒有連在一起」，而是加入了三個偽真實力學判定：  1. 懸臂樑效應 (Cantilever Moment) 力矩 M = W × D（重量 × 距離） 當 M > Rtens × 斷面積 → 連接點斷裂 → 純混凝土陽台伸出 3 格就會從根部斷掉 → 加了鋼筋的 RC 可以伸到 10+ 格  2. 載重累積 (Load Accumulation) BFS 沿路徑加總方塊重量，傳遞到支撐點 當 ΣW > Rcomp × 斷面積 → 支撐點壓碎 → 木柱撐不住 10 萬噸鐵堡壘  3. RC 融合加成 (RC Fusion Bonus) 相鄰鋼筋+混凝土的連接點 Rtens 大幅提升 → 有鋼筋的結構更強韌  架構定位（CTO 雙軌戰略）： Java 端 = 即時近似值（50ms 內

## 🔗 Related
- [[SupportPathAnalyzer]]
