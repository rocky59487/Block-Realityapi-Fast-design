---
id: "java_api:com.blockreality.api.client.render.optimization.BROcclusionCuller"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BROcclusionCuller

> [!info] 摘要
> GPU 硬體遮蔽查詢剔除器（Hardware Occlusion Query Culling）。  技術架構： - GL_SAMPLES_PASSED 或 GL_ANY_SAMPLES_PASSED 查詢 - 每個結構 section（16³ 區域）對應一個 query 物件 - 雙幀延遲讀回（避免 GPU stall）： Frame N: 提交 query → Frame N+1: 讀回 Frame N-1 的結果 - Bounding box 代理幾何（簡化 AABB 取代完整幾何做 query） - 漸進式查詢：只對螢幕投影面積大於閾值的 section 做 query - 與 frustum culling 協同：先 frustum cull，再對可見候選做 occlusion query  參考： - "GPU Pro" ch.12: Practical Occlusion Cu

## 🔗 Related
- [[AABB]]
- [[BROcclusionCuller]]
- [[query]]
- [[render]]
