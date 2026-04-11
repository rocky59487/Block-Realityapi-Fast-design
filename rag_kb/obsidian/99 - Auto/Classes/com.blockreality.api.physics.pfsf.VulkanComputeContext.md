---
id: "java_api:com.blockreality.api.physics.pfsf.VulkanComputeContext"
type: class
tags: ["java", "api", "pfsf", "client-only"]
---

# 🧩 com.blockreality.api.physics.pfsf.VulkanComputeContext

> [!info] 摘要
> PFSF Vulkan Compute 環境包裝。  職責： <ul> <li>初始化 Vulkan instance/device/compute queue（複用 BRVulkanDevice 如可用）</li> <li>VMA 記憶體分配器</li> <li>shaderc GLSL→SPIR-V 編譯</li> <li>Compute Pipeline 建立與管理</li> <li>Command Buffer 錄製與提交</li> </ul>  所有操作 graceful degradation：初始化失敗時 {@link #isAvailable()} 回傳 false， 呼叫端應 fallback 到 CPU 路徑。

## 🔗 Related
- [[BRVulkanDevice]]
- [[VulkanComputeContext]]
- [[compute]]
- [[isAvailable]]
- [[line]]
- [[queue]]
