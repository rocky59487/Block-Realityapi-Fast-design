---
id: "java_api:com.blockreality.api.client.rendering.vulkan.VkGBuffer"
type: class
tags: ["java", "api", "vulkan", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.vulkan.VkGBuffer

> [!info] 摘要
> VkGBuffer — 混合 GL+VK 架構的 GBuffer 管理。  <p>在 TIER_3 模式下，GBuffer 使用 OpenGL FBO（與現有管線相容）， 透過 GL/VK interop 將紋理共享給 Vulkan RT pass 使用。  <h3>GBuffer Layout</h3> <pre> Attachment 0: g_Albedo   — RGBA8  (RGB=albedo, A=AO) Attachment 1: g_Normal   — RGBA16F (octahedron encoded normal in XY) Attachment 2: g_Material — RGBA8  (roughness.r, metallic.g, matId.b, lod.a) Attachment 3: g_Motion   — RG16F  (screen-

## 🔗 Related
- [[VkGBuffer]]
- [[encode]]
- [[render]]
- [[ring]]
