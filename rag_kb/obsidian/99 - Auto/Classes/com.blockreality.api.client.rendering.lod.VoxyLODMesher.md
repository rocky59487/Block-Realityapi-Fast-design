---
id: "java_api:com.blockreality.api.client.rendering.lod.VoxyLODMesher"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.VoxyLODMesher

> [!info] 摘要
> Voxy LOD Mesher — 移植自 Voxy LodBuilder，4 級 3D LOD 網格生成。  <h3>LOD 等級策略</h3> <pre> LOD 0 (  0- 8 chunks) — 原始精度 16³，全三角形 BLAS LOD 1 (  8-32 chunks) — 2×2 合併 8³ LOD 2 ( 32-128 chunks)— 4×4 合併 4³ LOD 3 (128-512 chunks)— 8×8 合併 2³，SVDAG 軟追蹤 </pre>  <p>底層複用 {@link GreedyMesher} 產生合併面，然後依 LOD 等級降解析度。  @author Block Reality Team

## 🔗 Related
- [[Builder]]
- [[GreedyMesher]]
- [[VoxyLODMesher]]
- [[author]]
- [[render]]
- [[ring]]
