---
id: "dataflow:pfsf-full-pipeline"
type: dataflow
tags: ["dataflow", "pfsf", "physics", "gpu", "critical"]
---

# 🌊 PFSF 物理計算完整資料流

## 📝 概述
Minecraft World (BlockState) → StructureIslandRegistry 偵測結構島 → SnapshotBuilder 產生 RBlockState 快照 → SparsePhysicsSnapshot / VoxelSection 做空間分區 → PFSFDataBuilder 計算 conductivity/source/maxPhi/rcomp/rtens 並除以 sigmaMax → PFSFIslandBuffer 上傳 Vulkan buffer → jacobi_smooth / rbgs_smooth / pcg_matvec / phase_field_evolve shader 迭代求解 → failure_scan / failure_compact 找出失效方塊 → PFSFResultProcessor 讀回 StressField → PFSFFailureApplicator / ResultApplicator 應用到世界（標記破壞）→ CollapseManager 處理連鎖崩塌。

## 🔄 資料流階段
1. [[StructureIslandRegistry]]
2. [[SnapshotBuilder]]
3. [[SparsePhysicsSnapshot]]
4. [[PFSFDataBuilder]]
5. [[PFSFIslandBuffer]]
6. [[jacobi_smooth.comp.glsl]]
7. [[rbgs_smooth.comp.glsl]]
8. [[pcg_matvec.comp.glsl]]
9. [[phase_field_evolve.comp.glsl]]
10. [[failure_scan.comp.glsl]]
11. [[PFSFResultProcessor]]
12. [[PFSFFailureApplicator]]
13. [[CollapseManager]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - [[StructureIslandRegistry]]
>   - [[SnapshotBuilder]]
>   - [[SparsePhysicsSnapshot]]
>   - [[PFSFDataBuilder]]
>   - [[PFSFIslandBuffer]]
>   - [[jacobi_smooth.comp.glsl]]
>   - [[rbgs_smooth.comp.glsl]]
>   - [[pcg_matvec.comp.glsl]]
>   - [[phase_field_evolve.comp.glsl]]
>   - [[failure_scan.comp.glsl]]
>   - [[PFSFResultProcessor]]
>   - [[PFSFFailureApplicator]]
>   - [[CollapseManager]]
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/StructureIslandRegistry.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/SnapshotBuilder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/sparse/SparsePhysicsSnapshot.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFIslandBuffer.java`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFResultProcessor.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFFailureApplicator.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/collapse/CollapseManager.java`

## 🔗 Related Notes
- [[Builder]]
- [[CollapseManager]]
- [[IslandBuffer]]
- [[PFSFDataBuilder]]
- [[PFSFFailureApplicator]]
- [[PFSFIslandBuffer]]
- [[PFSFResultProcessor]]
- [[RBlock]]
- [[RBlockState]]
- [[Result]]
- [[ResultApplicator]]
- [[Snapshot]]
- [[SnapshotBuilder]]
- [[SparsePhysicsSnapshot]]
- [[State]]
- [[StressField]]
- [[StructureIsland]]
- [[StructureIslandRegistry]]
- [[VoxelSection]]
- [[compact]]
- [[fail]]
- [[rcomp]]
- [[rtens]]
- [[sigma]]
- [[smooth]]
