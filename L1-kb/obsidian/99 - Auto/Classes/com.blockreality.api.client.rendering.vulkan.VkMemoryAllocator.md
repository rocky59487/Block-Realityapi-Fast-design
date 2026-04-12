---
id: "java_api:com.blockreality.api.client.rendering.vulkan.VkMemoryAllocator"
type: class
tags: ["java", "api", "vulkan", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.vulkan.VkMemoryAllocator

> [!info] 摘要
> VMA（Vulkan Memory Allocator）封裝器（Phase 2-A）。  使用 LWJGL lwjgl-vma binding 管理所有 GPU 記憶體分配。 所有 Vulkan buffer 和 image 的記憶體分配統一透過此類別， 避免直接呼叫 vkAllocateMemory / vkFreeMemory。  分配類型： - Device-local（GPU 專用，最快，不可 CPU 讀寫） → 用於 BLAS/TLAS、頂點/索引 buffer、storage image - Host-visible（CPU 可寫入，GPU 可讀取） → 用於 staging buffer（資料上傳中轉） - Host-coherent（自動 flush/invalidate） → 用於 uniform buffer（每幀更新）  @see VkContext @see VkA

## 🔗 Related
- [[VkContext]]
- [[VkMemoryAllocator]]
- [[bind]]
- [[flush]]
- [[invalidate]]
- [[render]]
- [[ring]]
