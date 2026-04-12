---
id: "java_api:com.blockreality.api.physics.fluid.FluidAsyncCompute"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidAsyncCompute

> [!info] 摘要
> 流體三重緩衝非同步 GPU 計算管線。  <p>遵循 {@code PFSFAsyncCompute} 的三重緩衝模式： <pre> Tick N:   [CPU 準備資料]  [GPU 計算 N-1]  [CPU 讀取 N-2 結果] </pre>  <p>與 PFSF 共享 {@code VulkanComputeContext}（device、allocator）， 但使用獨立的 fence 池和 command buffer，避免互相干擾。  <p>延遲 2 tick（100ms）取得結果，但 CPU 和 GPU 永不互等。

## 🔗 Related
- [[FluidAsyncCompute]]
- [[PFSFAsyncCompute]]
- [[VulkanComputeContext]]
- [[tick]]
