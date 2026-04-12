---
id: "java_api:com.blockreality.api.client.render.optimization.BROptimizationEngine"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BROptimizationEngine

> [!info] 摘要
> Block Reality 優化引擎 — 融合 Sodium/Embeddium/Lithium 核心技術。  四大優化支柱： 1. Frustum Culling — 視錐剔除 + 結構邊界盒測試（Sodium 風格） 2. Greedy Meshing — 相鄰同材質面合併（Sodium Section Mesh 概念） 3. Render Batching — Draw call 合併 + 實例化渲染（Embeddium 風格） 4. Mesh Cache — 髒標記 VBO 快取（Sodium ChunkMeshData 概念）  與管線整合： - BRRenderPipeline 呼叫 renderStructureGeometry() → 優化引擎負責剔除、排序、批次提交 - 異步 Mesh 編譯（不阻塞主渲染執行緒）

## 🔗 Related
- [[BROptimizationEngine]]
- [[BRRenderPipeline]]
- [[line]]
- [[render]]
- [[renderStructureGeometry]]
