---
id: "java_api:com.blockreality.api.client.render.optimization.BRComputeSkinning"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRComputeSkinning

> [!info] 摘要
> GPU Compute Shader 骨骼蒙皮系統 — 將頂點蒙皮從 vertex shader 搬移至 compute shader。  技術架構： - OpenGL 4.3 Compute Shader 進行骨骼矩陣加權混合 - 三組 SSBO：骨骼矩陣（binding=0）、輸入頂點（binding=1）、輸出頂點（binding=2） - Work group size = 64，適合大多數 GPU warp/wavefront 大小 - 場景中 50+ 動畫實體時效能優勢明顯  應用場景： - 大量 collapse 動畫同時播放 - 結構體破壞時的碎片骨骼動畫 - 多實體建築場景中的動畫批次處理  參考： - "Skinning in a Compute Shader" (Wicked Engine 2017) - GPU Gems 3, ch.2: Animated Crow

## 🔗 Related
- [[BRComputeSkinning]]
- [[bind]]
- [[collapse]]
- [[compute]]
- [[render]]
- [[size]]
- [[vertex]]
