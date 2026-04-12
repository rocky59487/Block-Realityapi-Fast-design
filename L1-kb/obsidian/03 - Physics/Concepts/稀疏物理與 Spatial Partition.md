---
id: "pfsf:SparsePhysics"
type: pfsf
tags: ["pfsf", "sparse", "spatial-partition", "optimization"]
---

# 📄 稀疏物理與 Spatial Partition

## 📖 內容
為了處理大世界，PFSF 使用 SparseVoxelOctree、VoxelSection、SpatialPartitionExecutor 來只對有方塊的區域建立物理快照。ChunkPhysicsLOD 根據距離與重要性選擇不同的 LOD 層級。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/sparse/`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/ChunkPhysicsLOD.java`

## 🔗 Related Notes
- [[ChunkPhysicsLOD]]
- [[Partition]]
- [[SparseVoxelOctree]]
- [[SpatialPartitionExecutor]]
- [[VoxelSection]]
