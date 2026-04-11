---
id: "java_fd:com.blockreality.fastdesign.command.BlockChangeRecord"
type: class
tags: ["java", "fastdesign", "command"]
---

# 🧩 com.blockreality.fastdesign.command.BlockChangeRecord

> [!info] 摘要
> Delta Undo Engine — Fast Design v2.0  取代舊版全量快照 UndoManager。只儲存被修改方塊的差異記錄 (BlockChangeRecord)， 大幅降低記憶體消耗，支援 50+ 步歷史回退，10 萬方塊操作也不卡頓。  設計參考：WorldEdit 的 EditSession 差異追蹤 + Litematica 的 Schematic 差異比較機制。  執行緒安全：使用 ConcurrentHashMap + ConcurrentLinkedDeque。

## 🔗 Related
- [[BlockChangeRecord]]
- [[UndoManager]]
