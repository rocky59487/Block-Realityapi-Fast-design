---
id: "keyclass:BlockPhysicsEventHandler"
type: key_class
tags: ["key-class", "event", "forge", "physics"]
fqn: "com.blockreality.api.event.BlockPhysicsEventHandler"
thread_safety: "server event bus single-threaded"
---

# 🧩 BlockPhysicsEventHandler 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.event.BlockPhysicsEventHandler`
> **Thread Safety**: server event bus single-threaded

## 📋 職責
監聽 Forge 方塊放置/破壞事件，觸發結構島的登錄/註銷、RC 融合偵測、ConnectivityCache epoch 更新。使用 EventPriority.HIGH 確保在 Minecraft 預設邏輯之前執行。線程安全：在 Server event bus 單執行緒中執行。主要方法：onBlockPlace(BlockEvent.EntityPlaceEvent)、onBlockBreak(BlockEvent.BreakEvent)。修改注意：優先級過低可能導致方塊已移除後才計算應力，造成錯誤或空指針。

> [!warning] WARNING
> should keep EventPriority.HIGH

> [!tip] Metadata
> ⚠️ **WARNING**: should keep EventPriority.HIGH

## 🔗 Related Notes
- [[BlockPhysicsEventHandler]]
- [[ConnectivityCache]]
- [[Priority]]
- [[onBlockBreak]]
- [[onBlockPlace]]
