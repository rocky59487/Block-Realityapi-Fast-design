---
id: "keyclass:BRVulkanRT"
type: key_class
tags: ["key-class", "render", "vulkan", "rt", "client-only"]
fqn: "com.blockreality.api.client.render.rt.BRVulkanRT"
thread_safety: "render thread (CLIENT)"
---

# 🧩 BRVulkanRT 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.client.render.rt.BRVulkanRT`
> **Thread Safety**: render thread (CLIENT)

## 📋 職責
Vulkan 硬體光追渲染的核心協調器，管理 acceleration structure、ray tracing pipeline、shader binding table，並根據 GPU 架構（Ada/Blackwell）動態選擇 shader。線程安全：僅在渲染線程（CLIENT）執行。主要方法：renderFrame(RenderPassContext)、buildTLAS()、dispatchRays()。修改注意：此類直接操作 GPU 記憶體，任何資源泄漏都會導致驅動崩潰。

> [!warning] WARNING
> GPU memory leaks cause driver crash

> [!tip] Metadata
> ⚠️ **WARNING**: GPU memory leaks cause driver crash

## 🔗 Related Notes
- [[BRVulkanRT]]
- [[RenderPass]]
- [[RenderPassContext]]
- [[bind]]
- [[build]]
- [[dispatch]]
- [[dispatchRays]]
- [[line]]
- [[render]]
