---
id: "java_api:com.blockreality.api.physics.sparse.VoxelSection"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.VoxelSection

> [!info] 摘要
> 16³ 體素區段 — SparseVoxelOctree 的基本儲存單元。  三態設計（參考 Embeddium/Sodium 的 PalettedContainer）： EMPTY         — 全空氣，不分配陣列（零記憶體） HOMOGENEOUS   — 整段同材質，只儲存一個 RBlockState（16 bytes） HETEROGENEOUS — 混合材質，分配完整 4096 元素陣列（64 KB）  索引公式（Y-連續，與 RWorldSnapshot 一致）： lx + 16  (ly + 16  lz)  記憶體估算（建築場景 10% 非空氣）： 107K total sections × 10% heterogeneous = 10.7K × 64KB = 686 MB 對比全量 1D 陣列的 6.9 GB → 節省 90%  @since v3.0 Phase 

## 🔗 Related
- [[Palette]]
- [[RBlock]]
- [[RBlockState]]
- [[RWorldSnapshot]]
- [[Snapshot]]
- [[SparseVoxelOctree]]
- [[State]]
- [[VoxelSection]]
