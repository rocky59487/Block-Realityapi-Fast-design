---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.VulkanRTConfigNode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.VulkanRTConfigNode

> [!info] 摘要
> Vulkan 光線追蹤配置節點。  <p>允許使用者透過節點編輯器介面即時調整 Vulkan RT 的品質參數與預算控制開關。  <h3>端口分組</h3> <ul> <li><b>基本參數</b>：AO 半徑、最大彈射次數、降噪演算法</li> <li><b>效果開關（RTEffect 預算控制）</b>：RTAO、陰影、反射、SVGF 降噪、DAG GI</li> </ul>  <p>效果開關透過 {@link BRRTCompositor#enableRTEffect} / {@link BRRTCompositor#disableRTEffect} 直接作用於 {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline} 的 {@code EnumSet<RTEffect>}，無需重啟 RT pipeline。 

> [!tip] 資訊
> 🔼 Extends: [[BRNode]]

## 🔗 Related
- [[BRNode]]
- [[BRRTCompositor]]
- [[BRRTCompositor#disableRTEffect]]
- [[BRRTCompositor#enableRTEffect]]
- [[Config]]
- [[RTEffect]]
- [[VkRTPipeline]]
- [[VulkanRTConfigNode]]
- [[com.blockreality.api.client.rendering.vulkan.VkRTPipeline]]
- [[disableRTEffect]]
- [[enableRTEffect]]
- [[line]]
- [[render]]
- [[ring]]
