---
id: "java_api:com.blockreality.api.client.render.optimization.MergedFace"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.MergedFace

> [!info] 摘要
> ★ M3-fix: ThreadLocal 陣列池 — 避免每次 meshAxis() 呼叫重複分配臨時陣列。  mask[256]: 16×16 面遮罩（materialId，0 = 無面） visited[256]: 16×16 已訪問標記  每次使用前以 Arrays.fill() 清零，比 new int[256] 快約 3-5×（JIT 向量化）。 ThreadLocal 確保多執行緒 mesh 任務之間不共享狀態。

## 🔗 Related
- [[MergedFace]]
- [[Thread]]
- [[fill]]
- [[material]]
- [[materialId]]
- [[mesh]]
- [[meshAxis]]
- [[read]]
- [[render]]
- [[visit]]
