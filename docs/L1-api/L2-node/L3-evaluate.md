# EvaluateScheduler

> 所屬：L1-api > L2-node

## 概述

靜態排程器，每 frame/tick 評估活躍節點圖中的 dirty 節點，作為節點系統與遊戲主迴圈的橋接點。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `EvaluateScheduler` | `com.blockreality.api.node` | 靜態節點圖排程器 |

## 核心方法

### `init()`
- **說明**：初始化排程器，安全重複呼叫。清除活躍圖引用與統計資料

### `cleanup()`
- **說明**：釋放活躍圖引用，重置所有狀態

### `setActiveGraph(NodeGraph graph)`
- **說明**：設定當前活躍的節點圖。同一時間只能有一個活躍圖

### `getActiveGraph()`
- **回傳**：`NodeGraph`（可為 null）

### `tick()`
- **說明**：每 frame/tick 呼叫一次。檢查活躍圖是否有 dirty 節點，若有則呼叫 `NodeGraph.evaluate()`
- **呼叫位置**：`BRRenderPipeline.onRenderLevel` (AFTER_LEVEL) 或遊戲 tick

### `getLastEvalTimeNs()`
- **回傳**：`long`（上次評估耗時，奈秒）
- **用途**：效能監控

### `getDirtyNodesEvaluated()`
- **回傳**：`int`（上次 tick 評估的 dirty 節點數）

### `isInitialized()`
- **回傳**：`boolean`

## 生命週期

```
init() → setActiveGraph(graph) → tick() [每 frame 重複] → cleanup()
```

## 評估流程

1. 檢查 `initialized` 且 `activeGraph != null`
2. 呼叫 `activeGraph.hasDirtyNodes()` — 若無 dirty 節點則跳過
3. 記錄起始時間
4. 呼叫 `activeGraph.evaluate()` — 內部執行拓撲排序 + dirty 節點評估
5. 記錄評估耗時與節點數

## 執行緒模型

- 在客戶端 render thread 或 server tick thread 上同步執行
- 不跨執行緒 — 活躍圖的所有存取在同一執行緒內完成

## 關聯接口

- 依賴 → [NodeGraph](L3-node-graph.md) — 評估目標
- 被依賴 ← `BRRenderPipeline` — render loop 整合
- 被依賴 ← `fastdesign/` 節點編輯器 — 設定活躍圖
