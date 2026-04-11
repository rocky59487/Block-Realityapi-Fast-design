---
id: "java_api:com.blockreality.api.client.render.optimization.BRAsyncComputeScheduler"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRAsyncComputeScheduler

> [!info] 摘要
> 非同步計算排程器 — 利用 GL Fence Sync 管理跨幀 GPU 工作。  技術架構： - OpenGL 3.2+ Fence Sync 物件管理非同步 GPU 操作 - 雙緩衝 PBO（Pixel Buffer Object）非同步讀回 GPU 資料 - 任務佇列 + 優先級排程（High/Normal/Low） - 跨幀分攤策略：大型操作拆分為多個子任務，每幀執行一部分 - GPU 忙碌度偵測：fence 完成率追蹤，避免過度提交  應用場景： - SSGI 半解析度計算（隔幀更新） - Cloud ray march 1/4 解析度（隔 2 幀更新） - Occlusion query 結果讀回 - LOD mesh 上傳排程 - 3D LUT 重建（跨幀分攤）  參考： - "OpenGL Insights" ch.28: Asynchronous Buffer Trans

## 🔗 Related
- [[BRAsyncComputeScheduler]]
- [[Object]]
- [[mesh]]
- [[query]]
- [[render]]
