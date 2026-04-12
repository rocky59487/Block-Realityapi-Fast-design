---
id: "dataflow:rc-fusion"
type: dataflow
tags: ["dataflow", "material", "rc", "fusion"]
---

# 🌊 RC 融合偵測與應用資料流

## 📝 概述
玩家放置 Concrete 或 Rebar 方塊 → BlockPhysicsEventHandler 偵測方塊變更事件 → RCFusionDetector 掃描 6 鄰居尋找互補材料 → 若符合條件，計算融合後強度（97% 混凝土 + 3% 鋼筋）→ 產生 FusionCompletedEvent → 更新 RBlockEntity 的材料狀態 → StructureIslandRegistry 重新評估該島應力。

## 🔄 資料流階段
1. [[BlockPhysicsEventHandler]]
2. [[RCFusionDetector]]
3. [[FusionCompletedEvent]]
4. [[RBlockEntity]]
5. [[StructureIslandRegistry]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - [[BlockPhysicsEventHandler]]
>   - [[RCFusionDetector]]
>   - [[FusionCompletedEvent]]
>   - [[RBlockEntity]]
>   - [[StructureIslandRegistry]]
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/BlockPhysicsEventHandler.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/RCFusionDetector.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/FusionCompletedEvent.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/block/RBlockEntity.java`

## 🔗 Related Notes
- [[BlockPhysicsEventHandler]]
- [[FusionCompletedEvent]]
- [[RBlock]]
- [[RBlockEntity]]
- [[RCFusionDetector]]
- [[StructureIsland]]
- [[StructureIslandRegistry]]
