---
id: "keyclass:FluidGPUEngine"
type: key_class
tags: ["key-class", "fluid", "gpu", "physics"]
fqn: "com.blockreality.api.physics.fluid.FluidGPUEngine"
thread_safety: "synchronized with PFSF Vulkan context"
---

# 🧩 FluidGPUEngine 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.fluid.FluidGPUEngine`
> **Thread Safety**: synchronized with PFSF Vulkan context

## 📋 職責
流體模擬的 GPU 計算核心，透過 Vulkan Compute shader（fluid_jacobi、fluid_pressure）求解 Navier-Stokes 簡化版。預設關閉（BRConfig.isFluidEnabled() == false）。線程安全：與 PFSF 共用 Vulkan Compute 上下文，需注意 command buffer 同步。主要方法：simulateTick(FluidRegion)、applyBoundaryConditions()。修改注意：流體-結構耦合有 1 tick 延遲，這是設計如此，不是 bug。

> [!warning] WARNING
> Fluid-structure coupling has 1 tick delay by design

> [!tip] Metadata
> ⚠️ **WARNING**: Fluid-structure coupling has 1 tick delay by design

## 🔗 Related Notes
- [[BRConfig]]
- [[Config]]
- [[FluidGPUEngine]]
- [[FluidRegion]]
- [[apply]]
- [[isFluidEnabled]]
- [[tick]]
