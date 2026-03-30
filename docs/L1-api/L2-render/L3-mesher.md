# GreedyMesher 與 LOD 引擎

> 所屬：L1-api > L2-render

## 概述

Greedy Meshing 將相鄰同材質面合併為最大矩形，大幅減少頂點數；LOD 引擎根據攝影機距離自動選擇 5 級細節等級，支援高達 1024 格視距。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `GreedyMesher` | `client.render.optimization` | 經典 Greedy Meshing 演算法 |
| `MergedFace` | 內部類別 | 合併後的面資料 |
| `BRLODEngine` | `client.render.optimization` | 5 級 LOD 空間渲染優化 |

## GreedyMesher

### 演算法

參考 Mikola Lysenko (0fps) 的經典論文，對每個 16x16x16 section 執行：

1. 6 方向掃描（3 軸 x 正/反面）
2. 每個深度切片建立 16x16 面遮罩（材質 ID，0 = 空氣）
3. 鄰居遮擋檢測 — 被相鄰方塊擋住的面不生成
4. 掃描線 Greedy 合併 — 找最大矩形（同材質 + 未訪問）

### 核心方法

#### `mesh(int[] voxels)`
- **參數**：16x16x16 體素陣列（值為材質 ID，0 = 空氣）
- **回傳**：`List<MergedFace>`
- **效果**：一面 16x16 同材質牆 256 face → 1 face（256x 減少）

#### `faceToVertices(MergedFace, float[], int, float, float, float)`
- **說明**：將 MergedFace 轉換為頂點資料（每頂點 10 float: xyz + normal + rgba）

### MergedFace 結構

| 欄位 | 說明 |
|------|------|
| `axis` | 0=X, 1=Y, 2=Z |
| `positive` | 面朝正方向 |
| `u0, v0, u1, v1` | 切片座標範圍 |
| `depth` | 軸向位置 |
| `materialId` | 材質 ID |

## BRLODEngine

### LOD 等級

| 等級 | 距離範圍 | 幾何保留率 | 體素聚合比 |
|------|---------|-----------|-----------|
| FULL | 0~64 格 | 100% | 1:1 |
| HIGH | 64~192 格 | 75% | 1:2 |
| MEDIUM | 192~384 格 | 50% | 1:4 |
| LOW | 384~640 格 | 25% | 1:8 |
| MINIMAL | 640~1024 格 | 6% | 1:16 |

### 核心特性

- 四叉樹空間索引（O(log n) 查詢）
- 透視裕度（hysteresis）防抖動
- 接縫邊裙幾何處理
- VRAM 預算管理

#### `LODLevel.selectByDistance(double distance, double hysteresis)`
- **說明**：根據距離選擇最適合的 LOD 等級，考慮透視裕度防止頻繁切換

## 關聯接口

- 被依賴 ← [RenderPipeline](L3-pipeline.md) — GBuffer 幾何填充
- 依賴 → 材料系統 — 材質 ID 對應顏色查表
