# L2-sdf：有號距離場與等值面提取

> 所屬：L1-sidecar > L2-sdf

## 概述

SDF 子模組負責平滑路徑（`smoothing > 0`）的幾何處理：從離散方塊建構 3D 有號距離場（Signed Distance Field），再透過 Dual Contouring 提取等值面三角網格。

## 處理流程

```
BlockData[]
    |
buildSDFGrid()     -- 建構有號距離場網格
    |
SDFGrid
    |
computeCellHermiteData()  -- 計算邊上的交點與法線
    |
dualContour()      -- Dual Contouring 等值面提取
    |
Mesh (三角網格)
```

## 核心概念

| 概念 | 說明 |
|------|------|
| SDF | 純量場：負值=實體內部，正值=外部（空氣），零=表面 |
| Hermite 資料 | 邊上的零交叉點位置 + 表面法線 |
| QEF | 二次誤差函式，最小化頂點到各切面的距離平方和 |
| Dual Contouring | 每個包含符號變化的格子放置一個最佳頂點，再連接鄰格頂點形成四邊形 |

## 詳細文件

| 文件 | 說明 |
|------|------|
| [L3-sdf-grid.md](L3-sdf-grid.md) | SDFGrid 類別與 Hermite 資料計算 |
| [L3-dual-contouring.md](L3-dual-contouring.md) | Dual Contouring、QEF 求解器與 MeshBuilder |
