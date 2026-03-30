# Greedy Meshing

> 所屬：L1-sidecar > L2-pipeline

## 概述

Greedy Meshing 演算法將體素方塊直接轉換為最少數量的軸對齊矩形面，不經過 SDF，速度比 Dual Contouring 快 10-100 倍。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `greedyMesh` | `src/greedy-mesh.ts` | 主要匯出函式 |
| `packKey` | `src/greedy-mesh.ts` | 3D 座標打包為單一數字鍵 |

## 核心函式

### `greedyMesh(blocks)`
- **參數**: `blocks: BlockData[]` — 同一材質的方塊陣列
- **回傳**: `Mesh` — 含 `vertices`（Float64Array）、`indices`（Uint32Array）、`normals`（Float64Array）
- **說明**: 對每個軸方向的每個切片，以貪婪法合併相鄰同材質的暴露面為最大矩形，每個矩形輸出為 2 個三角形

### `packKey(x, y, z)`
- **參數**: `x, y, z: number` — 整數座標
- **回傳**: `number`
- **說明**: 將 3D 座標打包為單一數字：`((x+16384)*32768 + (y+16384))*32768 + (z+16384)`，避免字串分配的 GC 開銷

## 演算法流程

1. 建立佔用圖（occupancy map）：座標鍵 --> 材質
2. 對 6 個面方向（+/-X、+/-Y、+/-Z）分別處理：
   - 決定深度軸與兩個切片軸
   - 對每個深度切片，建立暴露面遮罩（相鄰格為空氣）
   - 貪婪合併：先沿 U 軸擴展寬度，再沿 V 軸擴展高度
   - 每個合併矩形輸出 4 個頂點 + 2 個三角形
3. 三角形繞序依面方向正負決定（正面順時針、負面逆時針）

## 效能特性

- 時間複雜度：O(6 * dimD * dimU * dimV)，即 O(方塊數 * 6)
- 使用數字鍵的 `Map` 查詢，避免字串 GC 壓力
- 產生的面數遠少於逐方塊輸出（大面合併）

## 參考

Mikola Lysenko, "Meshing in a Minecraft Game" (0fps.net)

## 關聯接口
- 被依賴 <-- [Pipeline Core](L3-pipeline-core.md)（`smoothing=0` 時呼叫）
- 輸出 --> [Mesh to B-Rep](../L2-cad/L3-mesh-to-brep.md)（產生 `Mesh` 供 CAD 轉換）
