---
id: "java_api:com.blockreality.api.client.AnchorPathRenderer"
type: class
tags: ["java", "api", "client", "client-only"]
---

# 🧩 com.blockreality.api.client.AnchorPathRenderer

> [!info] 摘要
> 錨定路徑渲染器 — 想法.docx AnchorPathVisualizer  在 RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS 階段， 以半透明線段渲染從 RBlock 到錨定點的 BFS 路徑。  ★ R6-10 fix: 想法.docx 規格色彩區分： - 有效錨定 = 綠色 RGBA(0.2, 0.85, 0.2, 0.6) - 未錨定   = 紅色 RGBA(1.0, 0.2, 0.2, 0.6)  設計： - 線段寬度 2px - 從方塊中心到方塊中心畫線 - 自動隨相機偏移（使用 PoseStack camera offset） - 超出 64 格渲染距離不繪製（效能保護）

## 🔗 Related
- [[AnchorPathRenderer]]
- [[RBlock]]
