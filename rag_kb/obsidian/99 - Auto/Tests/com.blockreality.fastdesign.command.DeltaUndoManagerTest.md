---
id: "java_test:com.blockreality.fastdesign.command.DeltaUndoManagerTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.fastdesign.command.DeltaUndoManagerTest

> [!info] 摘要
> DeltaUndoManager 一致性測試 — C-7  驗證 delta-based undo/redo 的邏輯一致性（不需要 Minecraft 世界）： - 初始狀態：無 undo/redo 可用 - Stack size 追蹤 - clear() 清空所有歷史 - onPlayerDisconnect 清理 - description peek  注意：captureBeforeState/commitChanges/undo/redo 需要 ServerLevel， 因此只測試狀態管理邏輯。

## 🔗 Related
- [[DeltaUndoManager]]
- [[DeltaUndoManagerTest]]
- [[State]]
- [[UndoManager]]
- [[capture]]
- [[captureBeforeState]]
- [[clear]]
- [[com.blockreality.fastdesign.command.DeltaUndoManager]]
- [[commitChanges]]
- [[connect]]
- [[description]]
- [[onPlayerDisconnect]]
- [[redo]]
- [[size]]
- [[undo]]
