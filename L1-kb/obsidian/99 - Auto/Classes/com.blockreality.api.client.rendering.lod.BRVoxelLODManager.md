---
id: "java_api:com.blockreality.api.client.rendering.lod.BRVoxelLODManager"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.BRVoxelLODManager

> [!info] 摘要
> BR Voxel LOD Manager — 4 級 3D LOD 系統頂層協調器。  <p>整合 {@link LODChunkManager}、{@link LODTerrainBuffer}、{@link LODRenderDispatcher}， 以及 Vulkan RT BLAS 更新（透過 {@code BRVulkanBVH}）。  <h3>呼叫順序（每幀）</h3> <pre> BRVoxelLODManager.beginFrame(proj, view, camX, camY, camZ, tick) BRVoxelLODManager.renderOpaque() BRVoxelLODManager.renderDepthPass()   ← CSM shadow BRVoxelLODManager.updateBLAS()        ← Vulkan RT（TI

## 🔗 Related
- [[BRVoxelLODManager]]
- [[BRVulkanBVH]]
- [[LODChunkManager]]
- [[LODRenderDispatcher]]
- [[LODTerrainBuffer]]
- [[begin]]
- [[beginFrame]]
- [[render]]
- [[renderDepthPass]]
- [[renderOpaque]]
- [[ring]]
- [[shadow]]
- [[tick]]
- [[update]]
- [[updateBLAS]]
