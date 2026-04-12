---
id: "pattern:event-handler"
type: pattern
tags: ["pattern", "event", "forge", "howto"]
---

# 🧩 如何新增 Forge 事件監聽器

## 🪜 步驟
- 1. 建立類別，使用 @SubscribeEvent 標註處理方法。
- 2. 使用 @EventBusSubscriber(modid = ..., bus = Bus.FORGE) 或在 mod 初始化時 MinecraftForge.EVENT_BUS.register(new Handler())。
- 3. 物理相關事件通常使用 EventPriority.HIGH。

> [!example] 範例類別: [[BlockPhysicsEventHandler]]

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/BlockPhysicsEventHandler.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/ServerTickHandler.java`

## 🔗 Related Notes
- [[BlockPhysicsEventHandler]]
- [[Priority]]
- [[ServerTickHandler]]
- [[register]]
