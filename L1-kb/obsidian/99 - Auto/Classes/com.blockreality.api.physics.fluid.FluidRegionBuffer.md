---
id: "java_api:com.blockreality.api.physics.fluid.FluidRegionBuffer"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidRegionBuffer

> [!info] 摘要
> 流體區域 GPU 緩衝包裝 — 每個活動流體區域一個實例。  <p>管理 Vulkan SSBO (Shader Storage Buffer Object) 的生命週期， 包括分配、上傳、讀回和釋放。遵循 {@code PFSFIslandBuffer} 的 雙緩衝 + 引用計數模式。  <h3>Buffer Layout (SoA, flat 3D, Morton-tiled 8×8×8)</h3> <pre> phiBufA[]       float32[N]   流體勢能（當前幀） phiBufB[]       float32[N]   流體勢能（前一幀，雙緩衝） densityBuf[]    float32[N]   密度 (kg/m³) volumeBuf[]     float32[N]   體積分率 [0,1] typeBuf[]       uint8[N]    

## 🔗 Related
- [[FluidRegion]]
- [[FluidRegionBuffer]]
- [[IslandBuffer]]
- [[Object]]
- [[PFSFIslandBuffer]]
- [[com.blockreality.api.physics.fluid.FluidRegion]]
- [[density]]
- [[flat]]
- [[type]]
- [[volume]]
