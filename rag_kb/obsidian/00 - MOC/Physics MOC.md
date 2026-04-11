---
id: moc-physics-moc
type: "moc"
tags: [moc]
---

# Physics MOC

## Concepts

| 標題 | 檔案 |
|------|------|
| [[AMGPreconditioner — 代數多網格預處理器]] | `AMGPreconditioner — 代數多網格預處理器.md` |
| [[BIFROST ML 訓練與模型註冊]] | `BIFROST ML 訓練與模型註冊.md` |
| [[C++ 獨立求解器（libpfsf）]] | `C++ 獨立求解器（libpfsf）.md` |
| [[OnnxPFSFRuntime — ML Surrogate 推理]] | `OnnxPFSFRuntime — ML Surrogate 推理.md` |
| [[PFSF 引擎總覽]] | `PFSF 引擎總覽.md` |
| [[PFSFBufferManager — GPU Buffer 管理]] | `PFSFBufferManager — GPU Buffer 管理.md` |
| [[PFSFDataBuilder — GPU 資料上傳建構器]] | `PFSFDataBuilder — GPU 資料上傳建構器.md` |
| [[PFSFEngine — 主入口與排程]] | `PFSFEngine — 主入口與排程.md` |
| [[PFSFFailureApplicator — 失效結果應用器]] | `PFSFFailureApplicator — 失效結果應用器.md` |
| [[PFSFResultProcessor — 結果後處理]] | `PFSFResultProcessor — 結果後處理.md` |
| [[VulkanComputeContext — GPU 上下文]] | `VulkanComputeContext — GPU 上下文.md` |
| [[稀疏物理與 Spatial Partition]] | `稀疏物理與 Spatial Partition.md` |

## Dataflows

| 標題 | 檔案 |
|------|------|
| [[FastDesign 節點圖求值資料流]] | `FastDesign 節點圖求值資料流.md` |
| [[ML 訓練到部署資料流]] | `ML 訓練到部署資料流.md` |
| [[PFSF ML Surrogate 快速路徑資料流]] | `PFSF ML Surrogate 快速路徑資料流.md` |
| [[PFSF 物理計算完整資料流]] | `PFSF 物理計算完整資料流.md` |
| [[RC 融合偵測與應用資料流]] | `RC 融合偵測與應用資料流.md` |
| [[Vulkan RT 渲染管線資料流]] | `Vulkan RT 渲染管線資料流.md` |
| [[應力熱圖同步資料流]] | `應力熱圖同步資料流.md` |
| [[流體-結構耦合資料流]] | `流體-結構耦合資料流.md` |
| [[網路封包生命周期（以 FastDesign 為例）]] | `網路封包生命周期（以 FastDesign 為例）.md` |

## 🔍 Dataview 動態查詢

> [!info] 需安裝 Dataview 外掛才能看到動態結果

```dataview
TABLE type, summary
FROM "03 - Physics/Concepts" OR "03 - Physics/Dataflows"
WHERE type != "moc"
SORT file.name ASC
LIMIT 50
```

---

返回 [[Home]]