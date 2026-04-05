# L2-cad：CAD 核心（OpenCASCADE）

> 所屬：L1-sidecar > L2-cad

## 概述

CAD 子模組透過 opencascade.js（OpenCASCADE 的 Emscripten WASM 封裝）將三角網格轉換為 B-Rep 實體，並匯出為 STEP 檔案。

## 處理流程

```
Mesh (三角網格)
    |
groupCoplanarTriangles()   -- 共面三角形分組
    |
meshToShape()              -- 建構面 + Sewing + 合併 + NURBS 轉換
    |
TopoDS_Shape (B-Rep 實體)
    |
makeCompound()             -- 多材質組合（可選）
    |
writeSTEP()                -- 匯出 STEP 檔案
    |
.step 檔案
```

## 後處理步驟

`meshToShape` 在 Sewing 後執行兩個後處理：

1. **ShapeUpgrade_UnifySameDomain**：合併同平面的相鄰面與共線邊（例如 12 三角形的立方體合為 6 個四邊面）
2. **BRepBuilderAPI_NurbsConvert**：將所有面轉為 B-Spline 曲面，使 STEP 輸出包含 `B_SPLINE_SURFACE` 實體，可供 FreeCAD/Rhino/SolidWorks 用於歷史操作

## 詳細文件

| 文件 | 說明 |
|------|------|
| [L3-mesh-to-brep.md](L3-mesh-to-brep.md) | 網格到 B-Rep 轉換與 OpenCASCADE 整合 |
| [L3-step-writer.md](L3-step-writer.md) | STEP 檔案寫入 |
