---
id: "java_api:com.blockreality.api.client.rendering.lod.LODChunkManager"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.LODChunkManager

> [!info] 摘要
> LOD Chunk Manager — 追蹤已載入的 section，調度 LOD 網格重建，管理 eviction。  <p>職責： <ul> <li>維護 section key → LODSection 的快取表</li> <li>接收 chunk dirty 通知，觸發非同步 LOD 重建</li> <li>依 lastUsedTick 驅逐閒置 section（LRU eviction）</li> <li>管理背景 worker 執行緒（最多 2 條）</li> </ul>  @author Block Reality Team

## 🔗 Related
- [[LODChunkManager]]
- [[LODSection]]
- [[author]]
- [[render]]
- [[ring]]
