---
id: "java_api:com.blockreality.api.physics.fluid.FluidRenderBridge"
type: class
tags: ["java", "api", "fluid", "client-only"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidRenderBridge

> [!info] 摘要
> 流體渲染橋接 — Compute↔Graphics 零拷貝 Buffer 共享。  <p>遵循 {@code PFSFRenderBridge} 的模式：透過 Pipeline Memory Barrier 讓 Graphics pipeline 直接讀取 Compute pipeline 的 velocity[] 和 volume[] buffer， 無需 CPU 中繼拷貝。  <h3>渲染資料流</h3> <pre> FluidGPUEngine compute → velocity[], volume[] buffers ↓ (pipeline barrier, zero-copy) FluidRenderBridge → 暴露 VkBuffer handles ↓ WaterSurfaceNode  → 讀取 volume 驅動水面高度 WaterCausticsNode → 讀取

## 🔗 Related
- [[FluidGPUEngine]]
- [[FluidRenderBridge]]
- [[PFSFRenderBridge]]
- [[WaterCausticsNode]]
- [[WaterSurfaceNode]]
- [[compute]]
- [[copy]]
- [[handle]]
- [[line]]
- [[volume]]
