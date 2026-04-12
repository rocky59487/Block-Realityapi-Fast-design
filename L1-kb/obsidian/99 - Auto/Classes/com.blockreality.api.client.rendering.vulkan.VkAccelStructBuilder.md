---
id: "java_api:com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder"
type: class
tags: ["java", "api", "vulkan", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder

> [!info] 摘要
> VkAccelStructBuilder — Ada/Blackwell LOD-aware BLAS + 物理骯髒追蹤 + Cluster AS。  <h3>LOD-aware BLAS 策略</h3> <pre> LOD 0-1 → 每 quad 一個緊湊 AABB（從 GreedyMesher 面資料擷取） 精確陰影/反射，prefer FAST_TRACE LOD 2-3 → 單一 section AABB（粗略，快速建構） prefer FAST_BUILD </pre>  <h3>SparseVoxelOctree 物理整合</h3> 每幀呼叫 {@link #onPhysicsDirtySections(SparseVoxelOctree)} 掃描 SVO dirty 區段， 並透過 {@link BRVulkanBVH#markDirty} 觸發對應 BLAS 增量更新。 

> [!tip] 資訊
> 🔌 Implements: [[BLASUpdater]]

## 🔗 Related
- [[AABB]]
- [[BLASUpdater]]
- [[BRVulkanBVH]]
- [[Builder]]
- [[GreedyMesher]]
- [[SparseVoxelOctree]]
- [[VkAccelStructBuilder]]
- [[markDirty]]
- [[onPhysicsDirtySections]]
- [[quad]]
- [[render]]
- [[ring]]
