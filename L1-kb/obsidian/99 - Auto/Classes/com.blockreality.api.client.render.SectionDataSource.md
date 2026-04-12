---
id: "java_api:com.blockreality.api.client.render.SectionDataSource"
type: class
tags: ["java", "api", "render", "client-only"]
---

# 🧩 com.blockreality.api.client.render.SectionDataSource

> [!info] 摘要
> 渲染管線的 Section 資料來源抽象。  <p>★ 架構修復：解耦 {@link PersistentRenderPipeline} 與 {@code SparseVoxelOctree}（物理層）。  <p>渲染管線只需知道「哪些 Section 需要重建」和「如何取得 Section 資料」， 不需要知道底層是八叉樹、HashMap 或其他空間結構。  <p>物理層的 {@code SparseVoxelOctree} 實作此介面（adapter pattern）， 渲染層只依賴此介面，不再直接 import 物理套件。  @since 1.1.0

## 🔗 Related
- [[PersistentRenderPipeline]]
- [[SectionDataSource]]
- [[SparseVoxelOctree]]
- [[line]]
- [[render]]
