---
id: "java_api:com.blockreality.api.client.render.ui.BRSelectionEngine"
type: class
tags: ["java", "api", "ui", "client-only"]
---

# 🧩 com.blockreality.api.client.render.ui.BRSelectionEngine

> [!info] 摘要
> Axiom 風格高階選取引擎 — 支援 Box / Magic Wand / Lasso / Brush 模式。  主要特性： - 多種選取工具，各有獨立的輸入處理邏輯 - Boolean 運算（Union / Intersect / Subtract）允許組合多次選取 - Tool Mask 限制操作只影響指定方塊類型 - Undo / Redo 歷史堆疊 - 選取資料以 BitSet 壓縮儲存（記憶體友善） - 全部座標為世界座標（int），不用浮點  線程安全：僅在客戶端主線程使用，無需同步。  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRSelectionEngine]]
- [[BitSet]]
- [[author]]
- [[render]]
