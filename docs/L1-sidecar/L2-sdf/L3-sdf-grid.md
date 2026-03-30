# SDF Grid 與 Hermite 資料

> 所屬：L1-sidecar > L2-sdf

## 概述

`SDFGrid` 儲存 3D 有號距離場，`hermite-data.ts` 在網格邊上偵測符號變化並計算交點位置與法線。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `SDFGrid` | `src/sdf/sdf-grid.ts` | 3D 有號距離場網格類別 |
| `buildSDFGrid` | `src/sdf/sdf-grid.ts` | 從方塊資料建構 SDF |
| `computeAABB` | `src/sdf/sdf-grid.ts` | 計算方塊的軸對齊包圍盒 |
| `computeCellHermiteData` | `src/sdf/hermite-data.ts` | 計算格子邊上的 Hermite 資料 |
| `EDGE_DIRS` | `src/sdf/hermite-data.ts` | 三個正方向邊的方向向量 |

## 核心函式

### `SDFGrid` 類別
- **欄位**: `data: Float32Array`、`sizeX/Y/Z`、`originX/Y/Z`、`cellSize`
- **方法**:
  - `index(ix, iy, iz)` — 3D 索引轉平面陣列索引
  - `get(ix, iy, iz)` / `set(ix, iy, iz, value)` — 讀寫 SDF 值
  - `inBounds(ix, iy, iz)` — 邊界檢查
  - `toWorld(ix, iy, iz)` — 網格座標轉世界座標
  - `toGrid(wx, wy, wz)` — 世界座標轉網格座標（未取整）

### `buildSDFGrid(blocks, resolution)`
- **參數**: `blocks: BlockData[]`；`resolution: number`（預設 1，範圍 1~4）
- **回傳**: `SDFGrid`
- **說明**: 三階段建構：
  1. **符號分類**：依佔用集合判定每個網格點在實體內（-1）或外（+1）
  2. **反向迴圈距離計算**：遍歷每個暴露面，更新鄰近網格點的最短距離平方（O(surfaceFaces * nearbyPoints)，比傳統方法快 ~100 倍）
  3. **寫入最終 SDF**：`sign * sqrt(distSq)`

### `computeCellHermiteData(grid, ix, iy, iz)`
- **參數**: `grid: SDFGrid`；`ix, iy, iz: number` — 網格座標
- **回傳**: `(HermiteEdge | null)[]`（長度 3，對應 +X/+Y/+Z 邊）
- **說明**: 檢查格子的三個正方向邊是否有符號變化，若有則透過線性插值找交點，並以三線性插值中心差分計算梯度法線

## 關鍵設計決策

- **半格偏移**：網格點採樣在方塊中心（x.5 位置），避免恰好落在體素表面上導致 distance=0，破壞符號變化偵測
- **數字鍵打包**：使用 `((x+16384)*32768 + (y+16384))*32768 + (z+16384)` 避免字串分配
- **梯度三線性插值**：在包含格子的 8 個角落計算中心差分，再依分數位置插值，產生比單點差分更平滑準確的法線

## 型別定義

```typescript
interface HermiteEdge {
  point: Vec3;   // 邊上的零交叉點
  normal: Vec3;  // 表面法線
}
```

## 關聯接口
- 依賴 --> `types.ts`（`BlockData`, `AABB`, `Vec3`, `HermiteEdge`）
- 被依賴 <-- [Dual Contouring](L3-dual-contouring.md)（讀取 SDFGrid 與 Hermite 資料）
- 被依賴 <-- [Pipeline Core](../L2-pipeline/L3-pipeline-core.md)（呼叫 `buildSDFGrid`）
