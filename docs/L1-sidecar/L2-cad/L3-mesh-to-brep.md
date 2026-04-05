# Mesh to B-Rep

> 所屬：L1-sidecar > L2-cad

## 概述

將三角網格轉換為 OpenCASCADE 的 `TopoDS_Shape`（B-Rep 實體或殼），整合共面合併、NURBS 轉換與實體化。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `initOpenCascade` | `src/cad/mesh-to-brep.ts` | 初始化 WASM 模組（約 2-5 秒） |
| `getOC` | `src/cad/mesh-to-brep.ts` | 取得已初始化的 OC 實例 |
| `meshToShape` | `src/cad/mesh-to-brep.ts` | 網格轉 B-Rep 主函式 |
| `OCCleaner` | `src/cad/mesh-to-brep.ts` | WASM 堆記憶體物件追蹤與清理 |
| `groupCoplanarTriangles` | `src/cad/mesh-to-brep.ts` | 共面三角形分組（Union-Find） |
| `buildCoplanarFace` | `src/cad/mesh-to-brep.ts` | 從共面三角形群組建構多邊形面 |
| `orderBoundaryLoop` | `src/cad/mesh-to-brep.ts` | 將邊界邊排序為封閉迴圈 |

## 核心函式

### `initOpenCascade()`
- **參數**: 無
- **回傳**: `Promise<void>`
- **說明**: 動態匯入 `opencascade.js/dist/node.js` 並初始化 WASM。僅需呼叫一次，重複呼叫會直接返回

### `meshToShape(mesh)`
- **參數**: `mesh: Mesh`
- **回傳**: `TopoDS_Shape`（any 型別）
- **說明**: 完整轉換流程：
  1. 將三角形依法線與平面距離分組為共面群組（`groupCoplanarTriangles`）
  2. 每個群組建構為一個多邊形面（或單一三角形面）
  3. `BRepBuilderAPI_Sewing` 縫合所有面（容差 1e-6）
  4. `ShapeUpgrade_UnifySameDomain` 合併同面/同邊
  5. `BRepBuilderAPI_NurbsConvert` 轉為 NURBS
  6. `BRepBuilderAPI_MakeSolid` 嘗試從 Shell 建立實體

### `groupCoplanarTriangles(mesh)`
- **參數**: `mesh: Mesh`
- **回傳**: `number[][]` — 三角形索引群組
- **說明**: 使用 Union-Find 合併共享邊且法線點積 > 0.999（約 2.5 度）且在同平面上（距離差 < 1e-4）的三角形。注意不合併反向法線（`dot > threshold` 而非 `abs(dot)`）

### `buildCoplanarFace(oc, cleaner, mesh, triangleIndices)`
- **參數**: OC 實例、記憶體清理器、網格、三角形索引陣列
- **回傳**: `TopoDS_Face | null`
- **說明**: 提取邊界邊（僅出現一次的邊），排序為封閉迴圈，建構為 `BRepBuilderAPI_MakePolygon` + `MakeFace`

## 記憶體管理

`OCCleaner` 追蹤所有在 WASM 堆上分配的 OC 物件，在 `finally` 區塊中統一呼叫 `.delete()` 釋放，防止記憶體洩漏。

## 關聯接口
- 依賴 --> opencascade.js（WASM CAD 核心）
- 被依賴 <-- [Pipeline Core](../L2-pipeline/L3-pipeline-core.md)（呼叫 `initOpenCascade`, `meshToShape`）
- 被依賴 <-- [STEP Writer](L3-step-writer.md)（呼叫 `getOC`）
