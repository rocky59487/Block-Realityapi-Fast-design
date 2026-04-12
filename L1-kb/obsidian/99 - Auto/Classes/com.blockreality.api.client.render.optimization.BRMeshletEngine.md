---
id: "java_api:com.blockreality.api.client.render.optimization.BRMeshletEngine"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRMeshletEngine

> [!info] 摘要
> Nanite-inspired Meshlet clustering system for Block Reality. <p> Academic reference: UE5 Nanite virtualized geometry (Karis 2021). Divides chunk meshes into clusters of max 128 triangles organized in a DAG hierarchy. The CPU performs frustum culling, backface-cone culling, and LOD selection per-meshlet; surviving meshlets are rendered via indirect draw commands. </p>

## 🔗 Related
- [[BRMeshletEngine]]
- [[Meshlet]]
- [[hierarchy]]
- [[mesh]]
- [[render]]
- [[ring]]
