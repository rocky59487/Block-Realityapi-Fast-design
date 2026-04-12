---
id: "java_api:com.blockreality.api.physics.sparse.SpatialPartitionExecutor"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.SpatialPartitionExecutor

> [!info] 摘要
> 空間分割並行執行器 — D-2b  將大型結構劃分為獨立的空間分區（Partition）， 使用 ForkJoinPool（Work-Stealing）並行執行物理計算。  設計原則： - 分區大小 = SVO Section（16³），天然無資料依賴 - 邊界 Section 需要相鄰數據 → 用 margin overlap 處理 - 負載平衡：ForkJoinPool 自動 work-stealing  使用方式： SpatialPartitionExecutor.execute(svo, section -> { // 在此 section 上執行物理計算 });

## 🔗 Related
- [[ForkJoinPool]]
- [[Partition]]
- [[SpatialPartitionExecutor]]
- [[execute]]
