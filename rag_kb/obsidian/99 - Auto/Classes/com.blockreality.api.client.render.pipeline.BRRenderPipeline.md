---
id: "java_api:com.blockreality.api.client.render.pipeline.BRRenderPipeline"
type: class
tags: ["java", "api", "pipeline", "client-only"]
---

# 🧩 com.blockreality.api.client.render.pipeline.BRRenderPipeline

> [!info] 摘要
> Block Reality 固化渲染管線 — 頂級渲染引擎核心。  架構融合： - Iris/Radiance: 多 Pass 延遲渲染（Shadow → GBuffer → Deferred → Composite → Final） - Sodium/Embeddium: Greedy Meshing + Frustum Culling + VBO 批次 - GeckoLib: 骨骼動畫插值系統  固化設計原則： 1. 所有 shader 在啟動時編譯，執行時零分配 2. 管線 pass 順序不可外部修改 3. FBO 自動管理 resize 4. 所有 uniform 通過 pre-computed 常數表更新  入口點： - {@link #init()} — 初始化（ClientSetup 呼叫一次） - {@link #onRenderLevel(RenderLevelStage

## 🔗 Related
- [[BRRenderPipeline]]
- [[ClientSetup]]
- [[compute]]
- [[init]]
- [[line]]
- [[onRenderLevel]]
- [[render]]
- [[resize]]
- [[size]]
