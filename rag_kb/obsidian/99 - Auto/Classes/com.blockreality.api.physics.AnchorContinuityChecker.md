---
id: "java_api:com.blockreality.api.physics.AnchorContinuityChecker"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.AnchorContinuityChecker

> [!info] 摘要
> 錨定連通性檢查器 (Feature 5)  規格（v3fix + 想法.docx）： 從 RC_NODE 或 REBAR 節點出發，沿鋼筋路徑做 6-connectivity BFS， 判斷是否能到達錨定點（地基/基岩）。  三種錨定源： 1. y ≤ level.getMinBuildHeight() + 1（最底層地板） 2. BlockState.below() == Blocks.BEDROCK（基岩鄰接） 3. RBlockEntity.blockType == BlockType.ANCHOR_PILE（手動錨定樁）  效能設計（dirty flag 快取）： - 結果快取：Map<BlockPos, Boolean>（ConcurrentHashMap 線程安全） - Dirty 標記：Set<BlockPos>（ConcurrentHashMap.newKeySet） - 

> [!tip] 資訊
> 🔌 Implements: [[IAnchorChecker]]

## 🔗 Related
- [[ANCHOR_PILE]]
- [[AnchorContinuityChecker]]
- [[BlockPos]]
- [[BlockType]]
- [[IAnchorChecker]]
- [[RBlock]]
- [[RBlockEntity]]
- [[State]]
- [[Type]]
- [[connect]]
- [[getMin]]
