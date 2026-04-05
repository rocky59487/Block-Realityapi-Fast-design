# L2-pipeline：轉換管線

> 所屬：L1-sidecar > L2-pipeline

## 概述

Pipeline 子模組是 MctoNurbs 的核心轉換引擎，負責將方塊資料經由雙路徑架構轉換為 STEP CAD 檔案。

## 雙路徑架構

```
輸入: BlockData[] + ConvertOptions
         |
    smoothing = 0?
     /        \
   是          否
   |            |
 Greedy     SDF Grid
 Meshing    建構
   |            |
   |        Dual Contouring
   |        等值面提取
    \        /
     MaterialMesh[]
         |
    meshToShape (OpenCASCADE)
         |
    makeCompound + writeSTEP
         |
    輸出: .step 檔案
```

## 管線階段

| 階段 | 進度回報 ID | 說明 |
|------|------------|------|
| 初始化 | `init` | 載入 OpenCASCADE WASM（約 2-5 秒） |
| 分組 | `grouping` | 依材質分組方塊 |
| 網格建構 | `greedy_mesh` / `sdf_build` | 依路徑產生三角網格 |
| 等值面提取 | `dc_extract` | Dual Contouring（僅平滑路徑） |
| STEP 匯出 | `step_export` | 網格轉 B-Rep 並寫入檔案 |
| 完成 | `complete` | 回傳輸出路徑 |

## 詳細文件

| 文件 | 說明 |
|------|------|
| [L3-pipeline-core.md](L3-pipeline-core.md) | `convertVoxelsToSTEP` 主流程 |
| [L3-greedy-mesh.md](L3-greedy-mesh.md) | Greedy Meshing 快速網格演算法 |
