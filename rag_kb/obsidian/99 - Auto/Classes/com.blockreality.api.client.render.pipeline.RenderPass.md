---
id: "java_api:com.blockreality.api.client.render.pipeline.RenderPass"
type: class
tags: ["java", "api", "pipeline", "client-only"]
---

# 🧩 com.blockreality.api.client.render.pipeline.RenderPass

> [!info] 摘要
> 渲染 Pass 枚舉 — 仿 Iris/ShadersMod 渲染管線架構。  固化管線 pass 順序： SHADOW → GBUFFER_TERRAIN → GBUFFER_ENTITIES → GBUFFER_BLOCK_ENTITIES → GBUFFER_TRANSLUCENT → DEFERRED_LIGHTING → COMPOSITE_SSAO → COMPOSITE_BLOOM → COMPOSITE_TONEMAP → FINAL → OVERLAY_UI → OVERLAY_EFFECT  每個 pass 對應一組固定的 FBO 綁定和 shader program。

## 🔗 Related
- [[RenderPass]]
- [[line]]
- [[render]]
