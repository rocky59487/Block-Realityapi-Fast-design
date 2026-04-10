# STEP Writer

> 所屬：L1-sidecar > L2-cad

## 概述

將 OpenCASCADE 的 `TopoDS_Shape` 匯出為 STEP (ISO 10303-21) 檔案，處理 Emscripten 虛擬檔案系統與 Node.js 實體檔案系統之間的銜接。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `writeSTEP` | `src/cad/step-writer.ts` | 寫入 STEP 檔案到磁碟 |
| `shapeToSTEPString` | `src/cad/step-writer.ts` | 將 Shape 轉為 STEP 字串（記憶體內） |
| `makeCompound` | `src/cad/step-writer.ts` | 將多個 Shape 組合為 Compound |

## 核心函式

### `writeSTEP(shape, filePath)`
- **參數**: `shape: TopoDS_Shape`；`filePath: string` — 輸出檔路徑
- **回傳**: `void`
- **說明**: 呼叫 `shapeToSTEPString` 取得 STEP 內容，再以 `writeFileSync` 寫入實體檔案

### `shapeToSTEPString(shape)`
- **參數**: `shape: TopoDS_Shape`
- **回傳**: `string` — STEP 檔案內容
- **說明**: 使用 `STEPControl_Writer` 將 Shape 轉移（Transfer）並寫入 Emscripten 虛擬路徑 `/tmp/_mctonurbs_output.step`，再從虛擬 FS 讀取內容並清理虛擬檔案

### `makeCompound(shapes)`
- **參數**: `shapes: any[]` — TopoDS_Shape 陣列
- **回傳**: `TopoDS_Compound`
- **說明**: 建立 `TopoDS_Compound`，使用 `BRep_Builder` 逐一加入各 Shape。每個材質的實體成為 Compound 的一個子組件

## Emscripten 虛擬 FS 銜接

opencascade.js 執行在 Emscripten 環境中，其檔案 I/O 操作在虛擬檔案系統上。寫入流程：

```
STEPControl_Writer.Write(virtualPath)
    --> oc.FS.readFile(virtualPath)  // 從虛擬 FS 讀取
    --> oc.FS.unlink(virtualPath)     // 清理虛擬檔案
    --> writeFileSync(realPath)       // 寫入實體檔案系統
```

虛擬暫存路徑：`/tmp/_mctonurbs_output.step`

## STEP 匯出設定

- Transfer 模式：`STEPControl_AsIs`（保留原始形狀類型）
- 輸出格式：ISO 10303-21 (STEP AP214)

## 關聯接口
- 依賴 --> [Mesh to B-Rep](L3-mesh-to-brep.md)（呼叫 `getOC` 取得 OC 實例）
- 被依賴 <-- [Pipeline Core](../L2-pipeline/L3-pipeline-core.md)（呼叫 `writeSTEP`, `makeCompound`）
