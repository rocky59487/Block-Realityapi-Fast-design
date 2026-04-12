---
id: "java_api:com.blockreality.api.client.rendering.lod.LODBlendManager"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.LODBlendManager

> [!info] 摘要
> LOD 混合管理器 — 基於相機距離計算每級 LOD 的混合 alpha， 使用 Bayer 4×4 有序抖動矩陣用於時間穩定性（不產生 TAA 鬼影）。  <p>混合區間（block 單位，相對 section 中心）： <ul> <li>LOD0→LOD1：8 chunks (128 blocks) 距離，16-block 混合區</li> <li>LOD1→LOD2：32 chunks (512 blocks) 距離，32-block 混合區</li> <li>LOD2→LOD3：128 chunks (2048 blocks) 距離，64-block 混合區</li> </ul>  <p>混合 alpha： <ul> <li>0.0 = 舊 LOD 完全可見</li> <li>1.0 = 新 LOD 完全可見</li> </ul>  <p>Bayer 4×4 矩陣用於抖動閾值生成（

## 🔗 Related
- [[LODBlendManager]]
- [[alpha]]
- [[render]]
- [[ring]]
