---
id: "java_api:com.blockreality.api.physics.RCFusionDetector"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.RCFusionDetector

> [!info] 摘要
> RC 融合偵測器 — 偵測相鄰鋼筋+混凝土自動產生 RC 融合節點。  設計哲學（來自 v3fix 手冊 + 想法.docx）： 現實工程中，鋼筋混凝土 (Reinforced Concrete) 的強度不是 鋼筋+混凝土的簡單加總，而是有經驗公式：  R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens (φ=0.8) R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear (φ=0.6) R_RC_comp  = R_concrete_comp × compBoost (1.1)  觸發時機： 1. 當 REBAR 或 CONCRETE 類型的 RBlock 被放置時 2. 掃描 6 個鄰居，看有沒有互補的材料 3. 如果有 → 將兩個方塊升級為 RC_NODE + 設定融合材料

## 🔗 Related
- [[RBlock]]
- [[RCFusionDetector]]
- [[info]]
