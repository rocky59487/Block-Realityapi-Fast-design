---
id: "java_api:com.blockreality.api.physics.fluid.FluidPipelineFactory"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidPipelineFactory

> [!info] 摘要
> 流體 Compute Pipeline 工廠 — 編譯 GLSL、建立 Vulkan Pipeline。  <p>遵循 {@code PFSFPipelineFactory} 的模式： 在 Mod 初始化時一次性建立所有 pipeline，運行時不再編譯。  <h3>管線列表</h3> <ol> <li>{@code fluid_jacobi} — 6 bindings, PC 28 bytes</li> <li>{@code fluid_pressure} — 6 bindings, PC 20 bytes</li> <li>{@code fluid_boundary} — 4 bindings, PC 20 bytes</li> </ol>

## 🔗 Related
- [[FluidPipelineFactory]]
- [[PFSFPipelineFactory]]
- [[bind]]
- [[line]]
