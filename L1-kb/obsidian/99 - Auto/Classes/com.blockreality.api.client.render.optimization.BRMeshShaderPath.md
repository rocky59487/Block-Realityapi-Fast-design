---
id: "java_api:com.blockreality.api.client.render.optimization.BRMeshShaderPath"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRMeshShaderPath

> [!info] 摘要
> GL 4.6 Mesh Shader fast path for Block Reality rendering.  <p>Inspired by Nvidium, this class leverages GL_NV_mesh_shader to perform per-meshlet frustum and cone culling entirely on the GPU, eliminating CPU-GPU synchronization overhead. On Nvidia Turing+ hardware, this path yields 2-3x FPS improvement over the traditional vertex pipeline.</p>  <p>If the GL_NV_mesh_shader extension is not available

## 🔗 Related
- [[BRMeshShaderPath]]
- [[leverages]]
- [[line]]
- [[mesh]]
- [[render]]
- [[ring]]
- [[tension]]
- [[vertex]]
