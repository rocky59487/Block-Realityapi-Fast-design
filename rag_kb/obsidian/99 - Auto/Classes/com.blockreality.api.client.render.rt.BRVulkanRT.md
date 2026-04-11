---
id: "java_api:com.blockreality.api.client.render.rt.BRVulkanRT"
type: class
tags: ["java", "api", "rt", "client-only"]
---

# 🧩 com.blockreality.api.client.render.rt.BRVulkanRT

> [!info] 摘要
> Main Vulkan ray tracing pipeline for Block Reality. Dispatches ray tracing for shadows, reflections, ambient occlusion, and global illumination.  <p>Uses VK_KHR_ray_tracing_pipeline to trace rays against the BLAS/TLAS built by {@code BRVulkanBVH}. The output is written to an image that is interoped to GL via {@code BRVulkanInterop} and then denoised by {@link BRSVGFDenoiser}.</p>

## 🔗 Related
- [[BRSVGFDenoiser]]
- [[BRVulkanBVH]]
- [[BRVulkanInterop]]
- [[BRVulkanRT]]
- [[denoise]]
- [[line]]
- [[render]]
- [[shadow]]
