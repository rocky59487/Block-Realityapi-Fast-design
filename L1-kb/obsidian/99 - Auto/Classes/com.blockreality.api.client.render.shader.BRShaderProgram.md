---
id: "java_api:com.blockreality.api.client.render.shader.BRShaderProgram"
type: class
tags: ["java", "api", "shader", "client-only"]
---

# 🧩 com.blockreality.api.client.render.shader.BRShaderProgram

> [!info] 摘要
> 固化 Shader Program 封裝。  特性： - Uniform location 快取（避免每幀 glGetUniformLocation） - Matrix4f 上傳使用 stack allocation（零 heap 分配） - 編譯錯誤完整日誌 - 不可修改 — 一旦連結即為最終狀態  靈感來源： - Iris glsl-transformer 的 shader 管理模式 - Radiance 的 Vulkan pipeline 概念（在 GL 層模擬不可變管線）

## 🔗 Related
- [[BRShaderProgram]]
- [[Matrix4f]]
- [[glsl]]
- [[line]]
- [[render]]
