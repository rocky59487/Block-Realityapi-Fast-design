# NurbsExporter

> 所屬：L1-fastdesign > [L2-sidecar-export](index.md)

## 概述

`NurbsExporter` 負責收集選取區域內的 `RBlockEntity` 方塊資料，組建符合 MctoNurbs `ConvertRequest` 格式的 JSON 請求，透過 `SidecarBridge` 以 JSON-RPC 2.0 呼叫 `dualContouring` 方法匯出 STEP 檔案。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `NurbsExporter` | `sidecar.NurbsExporter` | NURBS/STEP 匯出入口 |
| `NurbsExporter.ExportOptions` | `sidecar.NurbsExporter.ExportOptions` | 匯出參數配置（record 類型） |

## ExportOptions 配置

| 參數 | 類型 | 範圍 | 說明 |
|------|------|------|------|
| `smoothing` | `double` | 0.0 ~ 1.0 | 0.0 = 完全體素（Greedy Mesh），>0 = SDF + Dual Contouring 曲面化 |
| `resolution` | `int` | 1 ~ 4 | SDF 子體素解析度倍率，越高越細緻，指數級增加記憶體與時間 |
| `outputPath` | `String` | — | STEP 輸出路徑，`null` 時自動生成時間戳路徑 |

### 工廠方法

- `ExportOptions.defaults()` — smoothing=0.0, resolution=1（最快，完全體素）
- `ExportOptions.smooth()` — smoothing=0.5, resolution=1（中度曲面化）

## 核心方法

### `export(level, box)`
- **參數**: `ServerLevel` 世界, `SelectionBox` 選取區域
- **回傳**: `JsonObject` — Sidecar 回傳結果
- **說明**: 使用預設選項（完全體素）匯出

### `export(level, box, opts)`
- **參數**: `ServerLevel`, `SelectionBox`, `ExportOptions` 匯出選項
- **回傳**: `JsonObject` — 包含 `outputPath`, `blockCount`, `materialBreakdown` 等欄位
- **說明**: 完整匯出流程：
  1. 收集選取區域內所有 `RBlockEntity` 的座標與材料資訊
  2. 驗證方塊數量不超過 `FastDesignConfig.getExportMaxBlocks()`
  3. 解析輸出路徑（預設在 `config/blockreality/exports/`）
  4. 確保 `SidecarBridge` 已啟動（未啟動則自動啟動，60 秒逾時）
  5. 呼叫 RPC 方法 `dualContouring`，逾時由 `FastDesignConfig.getExportTimeoutSeconds()` 控制

### `collectBlockData(level, box)`（私有）
- **參數**: `ServerLevel`, `SelectionBox`
- **回傳**: `JsonArray` — 方塊資料陣列
- **說明**: 遍歷選取區域，跳過空氣方塊與非 `RBlockEntity`，收集以下欄位：

| 欄位 | 說明 |
|------|------|
| `relX`, `relY`, `relZ` | 相對於選取區最小角的本地座標 |
| `blockState` | Minecraft 方塊狀態字串 |
| `rMaterialId` | Block Reality 材料 ID |
| `rcomp` | 抗壓強度（擴充欄位） |
| `rtens` | 抗拉強度（擴充欄位） |
| `stressLevel` | 當前應力水平（擴充欄位） |
| `isAnchored` | 是否固定錨點（擴充欄位） |

## 匯出管線路徑

```
smoothing = 0.0  → GreedyMesh（快速、銳利邊緣）
smoothing > 0.0  → SDF Grid → Dual Contouring（平滑曲面）
```

## 錯誤處理

- 選取區域無 R-unit 方塊 → 拋出 `IOException`
- 超過最大匯出方塊數 → 拋出 `IOException`
- Sidecar 未運行且無法啟動 → 拋出 `IOException`
- RPC 呼叫失敗 → 將 `SidecarBridge.SidecarException` 包裝為 `IOException`

## 關聯接口
- 依賴 → `SidecarBridge`（API 層 stdio JSON-RPC 橋接）
- 依賴 → `RBlockEntity`（API 層方塊實體，提供材料與應力資料）
- 依賴 → `FastDesignConfig`（匯出上限與逾時配置）
- 被依賴 ← [FdCommandRegistry `/fd export`](../L2-command/L3-fd-commands.md)（指令層觸發匯出）
- 被依賴 ← [FdActionPacket `EXPORT`](../L2-network/L3-packets.md)（GUI 按鈕觸發匯出）
