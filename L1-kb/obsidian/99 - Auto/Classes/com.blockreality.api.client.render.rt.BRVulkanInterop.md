---
id: "java_api:com.blockreality.api.client.render.rt.BRVulkanInterop"
type: class
tags: ["java", "api", "rt", "client-only"]
---

# 🧩 com.blockreality.api.client.render.rt.BRVulkanInterop

> [!info] 摘要
> GL/VK Interop — OpenGL 與 Vulkan 之間的紋理共享。  使用 VK_KHR_external_memory + GL_EXT_memory_object 實現零拷貝共享。 Vulkan RT 渲染結果直接匯出為 GL texture，供後處理鏈合成。  Fallback：若 interop 不可用，使用 CPU readback 路徑（較慢）。  架構： Vulkan RT Pipeline → VkImage (RGBA16F) ↓ VK_KHR_external_memory (export fd) OpenGL Composite ← GL texture (import fd)

## 🔗 Related
- [[BRVulkanInterop]]
- [[line]]
- [[read]]
- [[render]]
