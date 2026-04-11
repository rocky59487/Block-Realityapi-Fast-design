---
id: "render:LOD-optimization"
type: render
tags: ["render", "lod", "optimization", "client-only"]
---

# 📄 LOD 與地形優化

## 📖 內容
BRLODEngine、BRSparseVoxelDAG、GreedyMesher、BRMeshletEngine、BRGPUCulling 共同實作多層級細節與剔除。BRDiskLODCache 將遠景 LOD 資料緩存到磁碟。VoxyLODMesher 與 SVDAGLOD3Tracer 提供稀疏體素 LOD 光追。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/optimization/BRLODEngine.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/optimization/BRSparseVoxelDAG.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/optimization/GreedyMesher.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/rendering/lod/`

## 🔗 Related Notes
- [[BRDiskLODCache]]
- [[BRGPUCulling]]
- [[BRLODEngine]]
- [[BRMeshletEngine]]
- [[BRSparseVoxelDAG]]
- [[GreedyMesher]]
- [[Meshlet]]
- [[SVDAGLOD3Tracer]]
- [[VoxyLODMesher]]
