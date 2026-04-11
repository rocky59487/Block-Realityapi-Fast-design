---
id: "rule:forge-event-priority"
type: rule
tags: ["rule", "forge", "event"]
---

# 📄 Forge 事件優先級

## 📖 內容
物理事件通常需要 EventPriority.HIGH，以確保在方塊破壞事件之前計算結構完整性。低優先級可能導致方塊已經被 Minecraft 移除後才計算應力，造成空指針或錯誤結果。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/BlockPhysicsEventHandler.java`

## 🔗 Related Notes
- [[BlockPhysicsEventHandler]]
- [[Priority]]
