---
id: "java_api:com.blockreality.api.physics.fluid.FluidJacobiRecorder"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidJacobiRecorder

> [!info] 摘要
> 流體 Jacobi GPU 指令記錄器。  <p>將 Jacobi 迭代、壓力計算和邊界提取的 GPU dispatch 指令 記錄到 {@link FluidAsyncCompute.FluidComputeFrame} 的 command buffer 中。  <p>遵循 {@code PFSFVCycleRecorder} 的記錄模式： 綁定 pipeline → 分配描述子集 → 綁定 buffer → push constants → dispatch。

## 🔗 Related
- [[ComputeFrame]]
- [[FluidAsyncCompute]]
- [[FluidComputeFrame]]
- [[FluidJacobiRecorder]]
- [[PFSFVCycleRecorder]]
- [[dispatch]]
- [[line]]
- [[push]]
