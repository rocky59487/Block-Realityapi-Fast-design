# Dual Contouring 與 QEF 求解器

> 所屬：L1-sidecar > L2-sdf

## 概述

Dual Contouring (DC) 從 SDF 網格提取等值面三角網格，透過 QEF 求解器在每個格子內放置最佳頂點，保留銳利邊角特徵。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `dualContour` | `src/dc/dual-contouring.ts` | DC 主函式 |
| `solveQEF` | `src/dc/qef-solver.ts` | 二次誤差函式求解器 |
| `jacobiEigen3x3` | `src/dc/qef-solver.ts` | 3x3 對稱矩陣特徵值分解 |
| `MeshBuilder` | `src/dc/mesh.ts` | 三角網格建構器 |

## 核心函式

### `dualContour(grid, smoothing)`
- **參數**: `grid: SDFGrid`；`smoothing: number`（預設 0）
- **回傳**: `Mesh`
- **說明**: 兩階段演算法：
  1. **放置頂點**：預計算並快取所有 Hermite 邊資料；對每個含符號變化的格子，收集其 12 條邊的 Hermite 資料，呼叫 `solveQEF` 求最佳頂點位置
  2. **生成四邊形**：對每條符號變化邊，連接共享該邊的 4 個格子的頂點，形成一個四邊形（2 個三角形）

### `solveQEF(hermiteEdges, cellMin, cellMax, smoothing)`
- **參數**: `hermiteEdges: HermiteEdge[]`；`cellMin/cellMax: Vec3`（格子邊界）；`smoothing: number`
- **回傳**: `QEFResult { position: Vec3, error: number }`
- **說明**: 最小化 `sum((n_i . (x - p_i))^2)`，建構 ATA (3x3 對稱矩陣) 與 ATB，以 SVD 偽逆求解。smoothing > 0 時加入質心正則化。結果限制在格子邊界內

### `MeshBuilder`
- **方法**:
  - `addVertex(v: Vec3): number` — 新增頂點，回傳索引
  - `addTriangle(i0, i1, i2)` — 新增三角形
  - `addQuad(i0, i1, i2, i3)` — 新增四邊形（拆為 2 個三角形）
  - `build(): Mesh` — 產出含面積加權法線的最終 Mesh

## QEF 求解器細節

### SVD 偽逆

對 3x3 對稱矩陣 ATA，使用 Jacobi 旋轉迭代求特徵值分解：

```
ATA = V * diag(eigenvalues) * V^T
x = V * diag(1/eigenvalue_i) * V^T * ATB
```

- 奇異值截斷閾值：`max(1e-6 * maxEigenvalue, 1e-12)`
- 截斷的分量回退到質心（mass point），保留銳利特徵

### 平滑正則化

當 `smoothing > 0` 時，對 ATA 對角線加上 `smoothing`，對 ATB 加上 `smoothing * massPoint`，將解偏向交點質心。正則化強度不隨邊數縮放，避免多邊格子過度平滑。

## 效能最佳化

- Hermite 資料預計算快取（`hermiteCache`），避免同一條邊被 2-4 個格子重複計算
- 邊去重使用數字鍵的 `Set`
- 特徵值排序確保 SVD 截斷閾值行為一致

## 關聯接口
- 依賴 --> [SDF Grid / Hermite](L3-sdf-grid.md)（`SDFGrid`, `computeCellHermiteData`, `EDGE_DIRS`）
- 被依賴 <-- [Pipeline Core](../L2-pipeline/L3-pipeline-core.md)（呼叫 `dualContour`）
- 輸出 --> [Mesh to B-Rep](../L2-cad/L3-mesh-to-brep.md)（產生 `Mesh` 供 CAD 轉換）
