# MctoNurbs Sidecar 模組總覽

> 所屬：L1-sidecar

## 概述

MctoNurbs Sidecar 是一個 TypeScript/Node.js 進程，負責將 Minecraft 方塊幾何體轉換為工業標準 STEP (CAD) 檔案。它透過 stdio JSON-RPC 2.0 協定與 Java Forge 模組 (`SidecarBridge`) 通訊，實現跨語言的 CAD 匯出管線。

## 技術棧

| 技術 | 用途 |
|------|------|
| TypeScript + Node.js | 執行環境與主要語言 |
| opencascade.js | OpenCASCADE WASM 封裝，提供 B-Rep 建模與 STEP 匯出 |
| JSON-RPC 2.0 | Java-TS 跨進程通訊協定 |

## 雙路徑架構

管線根據 `smoothing` 參數自動選擇路徑：

```
smoothing = 0   --> Greedy Meshing --> B-Rep --> STEP  (快速、銳利邊緣)
smoothing > 0   --> SDF --> Dual Contouring --> B-Rep --> STEP  (平滑曲面)
```

## 與 Java 模組的整合

- Java 端透過 `SidecarBridge` 以 stdio 啟動此進程
- 部署路徑：`GAMEDIR/blockreality/sidecar/dist/sidecar.js`
- 在 `fastdesign:processResources` 建置階段自動編譯
- 接收 `BlueprintBlock[]` 資料，回傳 `ConvertResult`

## 進入點

`src/index.ts` 支援雙模式：
1. **JSON-RPC 模式**：stdin 第一行包含 `"jsonrpc"` 時啟動，常駐直到收到 `shutdown`
2. **單次模式**：讀取完整 JSON 至 EOF 後處理，向後相容 CLI 測試

## 子模組導覽

| 子模組 | 說明 | 連結 |
|--------|------|------|
| L2-rpc | JSON-RPC 2.0 伺服器 | [L2-rpc/index.md](L2-rpc/index.md) |
| L2-pipeline | 轉換管線核心 | [L2-pipeline/index.md](L2-pipeline/index.md) |
| L2-sdf | 有號距離場系統 | [L2-sdf/index.md](L2-sdf/index.md) |
| L2-cad | CAD 核心（OpenCASCADE） | [L2-cad/index.md](L2-cad/index.md) |
| L2-ifc | **IFC 4.x 結構匯出**（P3-A） | [L2-ifc/index.md](L2-ifc/index.md) |

## 已註冊 RPC 方法

| 方法 | 說明 | Java 呼叫者 |
|------|------|-------------|
| `ping` | 連線測試 | `SidecarBridge.call("ping", ...)` |
| `dualContouring` | 體素→STEP 匯出 | `NurbsExporter.export()` |
| `ifc4Export` | **IFC 4.x 結構匯出**（P3-A） | `IfcExporter.export()` |

## 關鍵型別（`src/types.ts`）

| 型別 | 說明 |
|------|------|
| `BlueprintBlock` | Java 模組傳入的方塊資料，含座標、方塊狀態、材質 ID、物理屬性 |
| `DualContouringParams` | JSON-RPC `dualContouring` 方法的參數 |
| `ConvertResult` | 回傳給 Java 的結果，含輸出路徑與各材質統計 |
| `Ifc4ExportParams` | JSON-RPC `ifc4Export` 方法的參數（P3-A） |
| `Ifc4ExportResult` | `ifc4Export` 回傳結果，含元素分類統計與最大利用率 |
| `BlockData` | 內部格式的方塊資料（x, y, z, material） |
| `Mesh` | 三角網格（Float64Array 頂點 + Uint32Array 索引 + 法線） |

## 常數（`src/constants.ts`）

| 常數 | 值 | 說明 |
|------|-----|------|
| `MAX_BLOCK_COUNT` | 65,536 | 每次 RPC 呼叫的方塊上限 |
| `MAX_GRID_CELLS` | 4,000,000 | SDF 網格最大格數（防止 OOM） |
| `RESOLUTION_RANGE` | 1~4 | SDF 子體素解析度範圍 |
| `COORD_RANGE` | -16384~16383 | 座標有效範圍（位元打包限制） |
