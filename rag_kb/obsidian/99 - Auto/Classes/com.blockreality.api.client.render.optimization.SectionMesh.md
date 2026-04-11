---
id: "java_api:com.blockreality.api.client.render.optimization.SectionMesh"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.SectionMesh

> [!info] 摘要
> Mesh Cache — Sodium 風格 Section VBO 快取。  核心概念： - 每個 16³ section 的 Greedy Meshed 結果快取為一個 VBO - 髒標記機制：方塊變更時標記 section 為髒，下次渲染前重建 - LRU 淘汰：超過 MAX_SECTIONS 時移除最久未使用的  Sodium ChunkMeshData 啟發： - 分離 mesh 編譯（可異步）和 GPU 上傳（必須主執行緒） - 快取 key = sectionPos（packed long: x|y|z）

## 🔗 Related
- [[SectionMesh]]
- [[mesh]]
- [[render]]
