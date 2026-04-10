# 轉換管線核心

> 所屬：L1-sidecar > L2-pipeline

## 概述

`convertVoxelsToSTEP` 是整個 Sidecar 的主要轉換函式，將方塊陣列經由雙路徑架構產生 STEP 檔案。

## 關鍵類別/函式

| 名稱 | 檔案路徑 | 說明 |
|------|---------|------|
| `convertVoxelsToSTEP` | `src/pipeline.ts` | 主轉換管線入口 |
| `PipelineError` | `src/pipeline.ts` | 帶有 `data` 上下文的錯誤類別 |
| `groupByMaterial` | `src/pipeline.ts` | 依材質分組方塊 |
| `estimateGridSize` | `src/pipeline.ts` | 預估 SDF 網格大小（記憶體守護） |
| `ProgressCallback` | `src/pipeline.ts` | 進度回呼型別 |

## 核心函式

### `convertVoxelsToSTEP(request, onProgress?)`
- **參數**:
  - `request: ConvertRequest` — 包含 `blocks: BlockData[]` 與 `options: ConvertOptions`
  - `onProgress?: ProgressCallback` — 可選的進度回呼
- **回傳**: `Promise<ConvertResult>` — 包含 `success`、`outputPath`、`blockCount`、`materialBreakdown`
- **說明**: 執行完整轉換流程：
  1. 初始化 OpenCASCADE WASM
  2. 依材質（`rMaterialId`）將方塊分組
  3. `smoothing=0` 走 Greedy Mesh 路徑，`>0` 走 SDF+DC 路徑
  4. 每個材質產生獨立的 `MaterialMesh`
  5. 轉換為 B-Rep Shape，組合為 Compound，匯出 STEP

### `groupByMaterial(blocks)`
- **參數**: `blocks: BlockData[]`
- **回傳**: `Record<string, BlockData[]>`
- **說明**: 將方塊依 `material` 欄位分組，空材質歸入 `'default'`

### `estimateGridSize(blocks, resolution)`
- **參數**: `blocks: BlockData[]`；`resolution: number`
- **回傳**: `number` — 預估的網格格數
- **說明**: 計算 `(maxX-minX+1+2*padding) * resolution + 1` 三軸相乘，超過 `MAX_GRID_CELLS`（400 萬）時拒絕執行

## 型別定義

```typescript
type ProgressCallback = (msg: StatusMessage) => void;

interface ConvertOptions {
  smoothing: number;     // 0.0 = Greedy Mesh, 0.01~1.0 = SDF+DC
  outputPath: string;
  resolution?: number;   // 1~4, 預設 1
}
```

## 錯誤處理

| 情境 | 錯誤訊息 | data 欄位 |
|------|---------|-----------|
| resolution 超出範圍 | `Resolution X out of valid range` | `resolution`, `validRange` |
| SDF 網格過大 | `SDF grid too large` | `stage`, `blockCount`, `gridSize`, `resolution` |
| 無幾何產生 | `No geometry generated from input blocks` | `stage`, `blockCount`, `materialCount` |

## 關聯接口
- 依賴 --> [SDF Grid](../L2-sdf/L3-sdf-grid.md)（`buildSDFGrid`）
- 依賴 --> [Dual Contouring](../L2-sdf/L3-dual-contouring.md)（`dualContour`）
- 依賴 --> [Greedy Mesh](L3-greedy-mesh.md)（`greedyMesh`）
- 依賴 --> [Mesh to B-Rep](../L2-cad/L3-mesh-to-brep.md)（`initOpenCascade`, `meshToShape`）
- 依賴 --> [STEP Writer](../L2-cad/L3-step-writer.md)（`writeSTEP`, `makeCompound`）
- 被依賴 <-- [RPC Server](../L2-rpc/L3-rpc-server.md)（經由 `index.ts` 呼叫）
