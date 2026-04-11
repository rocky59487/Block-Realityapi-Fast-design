---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.MegaGeometryNode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.MegaGeometryNode

> [!info] 摘要
> Mega Geometry / Cluster BVH 配置節點（Phase 7）。  <p>控制 Blackwell Cluster BVH（{@link BRClusterBVH}）的合併策略與更新預算。 在非 Blackwell GPU 上此節點為 passthrough（所有設定靜默忽略）。  <h3>端口說明</h3> <ul> <li>{@code enableCluster}    — 啟用 Cluster BVH（需 Blackwell SM10+）</li> <li>{@code maxRebuildPerFrame} — 每幀最多重建的 Cluster 數（1-8；越高更新越即時）</li> <li>{@code clusterSize}      — 每個 Cluster 的 Section 數（2-8；固定 4×4=16）</li> </ul>  @see BRCl

> [!tip] 資訊
> 🔼 Extends: [[BRNode]]

## 🔗 Related
- [[BRClusterBVH]]
- [[BRNode]]
- [[MegaGeometryNode]]
- [[build]]
- [[line]]
- [[render]]
