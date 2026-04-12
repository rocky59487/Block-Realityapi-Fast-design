---
id: "java_api:com.blockreality.api.chisel.VoxelGrid"
type: class
tags: ["java", "api", "chisel"]
---

# 🧩 com.blockreality.api.chisel.VoxelGrid

> [!info] 摘要
> 10×10×10 子體素網格 — 每格 0.1m，儲存為 long[16]（1000 bits / 64 = 16 longs）。  索引方式：index = x + 10  (y + 10  z) 與 RWorldSnapshot 的 Y 連續慣例一致。  設計考量： - 不可變物件（建構後不可修改），使用 Builder 進行編輯 - 128 bytes 適合兩條 CPU cache line - NBT 序列化使用 putLongArray，極為緊湊 - 選用 long[] 而非 BitSet 以避免動態大小開銷

## 🔗 Related
- [[BitSet]]
- [[Builder]]
- [[RWorldSnapshot]]
- [[Snapshot]]
- [[VoxelGrid]]
- [[index]]
- [[line]]
