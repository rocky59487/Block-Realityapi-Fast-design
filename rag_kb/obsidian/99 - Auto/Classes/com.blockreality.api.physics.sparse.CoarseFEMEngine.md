---
id: "java_api:com.blockreality.api.physics.sparse.CoarseFEMEngine"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.CoarseFEMEngine

> [!info] 摘要
> 粗粒度有限元素引擎 — 階層式物理 Layer 2。  核心概念： 每個 VoxelSection (16³) 視為 FEM 的一個元素節點。 材質屬性 = Section 內所有非空方塊的加權平均。 用 Gauss-Seidel 迭代求解 Section 級的應力場。  壓縮比：4096:1（每 Section 4096 blocks → 1 node） 目標：10K active sections → 100-500ms 完成  結果用途： 1. StressHeatmapRenderer 的 LOD 顯示（遠距 Section 用粗估值） 2. 識別高應力 Section → 觸發 Layer 1 精確分析 3. 全域應力分佈概覽  @since v3.0 Phase 2

## 🔗 Related
- [[CoarseFEMEngine]]
- [[StressHeatmapRenderer]]
- [[VoxelSection]]
