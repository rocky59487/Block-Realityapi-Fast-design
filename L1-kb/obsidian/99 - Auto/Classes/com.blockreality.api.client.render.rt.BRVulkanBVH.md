---
id: "java_api:com.blockreality.api.client.render.rt.BRVulkanBVH"
type: class
tags: ["java", "api", "rt", "client-only"]
---

# 🧩 com.blockreality.api.client.render.rt.BRVulkanBVH

> [!info] 摘要
> BVH（Bounding Volume Hierarchy）管理器 — Vulkan RT 加速結構。  <p>場景階層： <pre> Scene TLAS (Top-Level) ├── Chunk Section BLAS (16×16×16) × N │   └── AABBs from GreedyMesher └── Updated incrementally per-frame </pre>  <p>每幀最多重建 {@link #MAX_BLAS_REBUILDS_PER_FRAME} 個 dirty BLAS， 避免 GPU stall。TLAS 在有任何 dirty section 時完整重建。

## 🔗 Related
- [[AABB]]
- [[BRVulkanBVH]]
- [[GreedyMesher]]
- [[from]]
- [[render]]
