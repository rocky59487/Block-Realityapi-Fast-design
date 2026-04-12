---
id: "java_api:com.blockreality.api.client.rendering.lod.SVDAGLOD3Tracer"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.SVDAGLOD3Tracer

> [!info] 摘要
> SVDAG LOD3 追蹤器 — 遠場 (2048+ blocks) 的 Sparse Voxel DAG 計算著色器渲染。  <p>流程： <ul> <li>BRSparseVoxelDAG 序列化為 GPU SSBO 格式</li> <li>上傳至 Vulkan device buffer</li> <li>建立計算管線（lod3_svdag_trace.comp.glsl）</li> <li>每幀在相機距離 > 128 chunks 時調度計算著色器</li> </ul>  <p>輸出： <ul> <li>Color image (rgba16f) — 最終著色</li> <li>Depth image (r32f) — 線性深度（block 單位）</li> </ul>  @author Block Reality Team

## 🔗 Related
- [[BRSparseVoxelDAG]]
- [[SVDAGLOD3Tracer]]
- [[author]]
- [[glsl]]
- [[lod3_svdag_trace.comp.glsl]]
- [[render]]
- [[ring]]
