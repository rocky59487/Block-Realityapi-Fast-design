---
id: "java_api:com.blockreality.api.client.render.rt.BRSVGFDenoiser"
type: class
tags: ["java", "api", "rt", "client-only"]
---

# 🧩 com.blockreality.api.client.render.rt.BRSVGFDenoiser

> [!info] 摘要
> SVGF (Spatiotemporal Variance-Guided Filtering) denoiser for 1-spp ray tracing output.  <p>Operates on OpenGL textures (already interoped from Vulkan via {@code BRVulkanInterop}) using GL compute shaders. This avoids Vulkan compute complexity and reuses existing GL infrastructure.</p>  <h3>Algorithm (3 passes):</h3> <ol> <li><b>Temporal Accumulation</b> — Reproject history using motion vectors, bl

## 🔗 Related
- [[Algorithm]]
- [[BRSVGFDenoiser]]
- [[BRVulkanInterop]]
- [[compute]]
- [[denoise]]
- [[from]]
- [[read]]
- [[render]]
- [[ring]]
