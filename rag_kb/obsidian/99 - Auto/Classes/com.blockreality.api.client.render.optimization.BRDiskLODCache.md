---
id: "java_api:com.blockreality.api.client.render.optimization.BRDiskLODCache"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRDiskLODCache

> [!info] 摘要
> Bobby-style disk-based LOD chunk cache for offline LOD computation and reload.  <p>Stores compressed LOD mesh data to disk so that previously computed LOD chunks can be reloaded instantly on subsequent sessions. Each section is stored as an individual file under {@code .minecraft/blockreality/lod-cache/}.</p>  <p>File format ({@code .brlod}): magic "BRLOD\0", version (int), vertexCount (int), inde

## 🔗 Related
- [[BRDiskLODCache]]
- [[compute]]
- [[line]]
- [[load]]
- [[mesh]]
- [[prev]]
- [[reload]]
- [[render]]
- [[vertex]]
