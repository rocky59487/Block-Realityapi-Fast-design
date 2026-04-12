---
id: "java_api:com.blockreality.api.physics.SnapshotBuilder"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.SnapshotBuilder

> [!info] 摘要
> 在主執行緒中以 ChunkSection 批次讀取方塊，建立唯讀快照。  關鍵效能設計： 1. 以 chunk section 為單位遍歷，chunk 查找次數 = 跨越的 section 數（非方塊數） 2. section.getBlockState(lx, ly, lz) 是 PalettedContainer 的陣列索引，O(1) 3. hasOnlyAir() 提前跳過空 section，大幅減少無效遍歷 4. 不強制加載 chunk (getChunkNow)，避免卡死主執行緒

## 🔗 Related
- [[Builder]]
- [[Palette]]
- [[Snapshot]]
- [[SnapshotBuilder]]
- [[State]]
- [[getBlock]]
- [[getBlockState]]
