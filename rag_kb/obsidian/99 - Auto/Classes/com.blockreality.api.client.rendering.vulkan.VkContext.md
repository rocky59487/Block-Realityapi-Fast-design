---
id: "java_api:com.blockreality.api.client.rendering.vulkan.VkContext"
type: class
tags: ["java", "api", "vulkan", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.vulkan.VkContext

> [!info] 摘要
> Vulkan 執行上下文 — 封裝 VkInstance / VkPhysicalDevice / VkDevice。  在 Minecraft 1.20.1（OpenGL 渲染器）中，Vulkan 路徑為「可選 Tier-3」： 若平台不支援 Vulkan，此 context 將保持 null， 所有依賴此類別的子系統（VkMemoryAllocator、VkAccelStructBuilder 等） 應在 {@code isAvailable()} 為 false 時降級至 OpenGL 路徑。  @see VkMemoryAllocator @see VkAccelStructBuilder

## 🔗 Related
- [[Builder]]
- [[Tier]]
- [[VkAccelStructBuilder]]
- [[VkContext]]
- [[VkDevice]]
- [[VkInstance]]
- [[VkMemoryAllocator]]
- [[VkPhysicalDevice]]
- [[isAvailable]]
- [[render]]
- [[ring]]
