# SparseVoxelOctree / CoarseFEMEngine — 稀疏體素與粗粒度 FEM

> 所屬：L1-api > L2-physics

## 概述

v3.0 的核心空間資料結構與階層式物理分析。SparseVoxelOctree 以 16³ VoxelSection 為基本單元管理大規模世界快照，CoarseFEMEngine 在 Section 級執行粗粒度應力分析。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `SparseVoxelOctree` | `physics.sparse.SparseVoxelOctree` | 稀疏體素八叉樹，`@NotThreadSafe` |
| `CoarseFEMEngine` | `physics.sparse.CoarseFEMEngine` | Section 級 Gauss-Seidel SOR 應力分析 |
| `VoxelSection` | `physics.sparse.VoxelSection` | 16³ 方塊區段 |
| `IncrementalSnapshotBuilder` | `physics.sparse.IncrementalSnapshotBuilder` | 增量快照建構器 |
| `RegionConnectivityEngine` | `physics.sparse.RegionConnectivityEngine` | 區域連通性分析 |
| `SpatialPartitionExecutor` | `physics.sparse.SpatialPartitionExecutor` | 空間分區並行執行器 |

## 核心方法

### `SparseVoxelOctree(minX, minY, minZ, maxX, maxY, maxZ)`
- **說明**: 建立指定範圍的八叉樹。記憶體用量正比於非空體積。

### `SparseVoxelOctree.getBlock(x, y, z)` / `setBlock(x, y, z, state)`
- **複雜度**: O(1)（hash lookup + array index）
- **說明**: 空氣區段不分配記憶體。累計移除達 1024 次自動觸發 compact()。

### `CoarseFEMEngine.analyze(SparseVoxelOctree)`
- **回傳**: `CoarseFEMResult`
- **說明**: Section 級粗粒度應力分析。壓縮比 4096:1。目標 10K sections 在 100-500ms 完成。

## SVO 技術規格

| 項目 | 值 |
|------|-----|
| Section 邊長 | 16 方塊 |
| Section Key 編碼 | 60-bit packed long（X: 20 bit, Y: 12 bit, Z: 28 bit） |
| 支援範圍 | X/Z ±8,388,608 blocks，Y ±32,768 blocks |
| 設計容量 | 1200x1200x300（432M blocks） |
| 自動壓縮閾值 | 累計移除 1024 次 |

## CoarseFEM 演算法

1. 為每個非空 Section 計算加權平均材質屬性
2. 計算 Section 自重（密度 x nonAirCount x g）
3. 識別錨定 Section（含錨定方塊或在底層）
4. Gauss-Seidel SOR 迭代（omega=1.4，最大 50 次，收斂閾值 0.5%）
5. 計算每個 Section 的應力利用率

## 關聯接口

- 依賴 → [PhysicsConstants](L3-beam-stress.md)（重力常數）
- 依賴 → [RBlockState](L3-force-solver.md)（方塊狀態）
- 被依賴 ← StressHeatmapRenderer（LOD 顯示）
- 被依賴 ← Layer 1 精確分析觸發
