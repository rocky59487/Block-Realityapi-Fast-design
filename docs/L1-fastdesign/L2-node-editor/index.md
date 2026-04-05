# 節點編輯器系統總覽

> 所屬：L1-fastdesign > L2-node-editor

## 概述

Fast Design 內建 Grasshopper 風格的視覺化節點編輯器，以 DAG（有向無環圖）表示渲染管線、材料系統、物理引擎與工具配置。使用者可在無限平移/縮放畫布上建立、連線、分組節點，即時預覽效果。

## 核心架構

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRNode` | `client.node.BRNode` | 節點抽象基類（DAG 計算單元） |
| `NodeGraph` | `client.node.NodeGraph` | DAG 管理器（節點/連線/群組，Kahn 拓撲排序） |
| `NodeRegistry` | `client.node.NodeRegistry` | 節點型別註冊表（typeId → 工廠） |
| `EvaluateScheduler` | `client.node.EvaluateScheduler` | 每幀評估髒節點（依拓撲順序） |
| `Wire` | `client.node.Wire` | 連線（OutputPort → InputPort） |
| `InputPort` / `OutputPort` | `client.node.InputPort` / `OutputPort` | 輸入/輸出端口 |
| `PortType` | `client.node.PortType` | 端口型別枚舉（FLOAT, INT, BOOL, COLOR, TEXTURE, VEC2/3/4, ENUM, STRUCT, MATERIAL, BLOCK, SHAPE, CURVE） |
| `NodeColor` | `client.node.NodeColor` | 節點分類顏色 |
| `NodeGroup` | `client.node.NodeGroup` | 節點群組（視覺分組） |
| `NodeGraphIO` | `client.node.NodeGraphIO` | 節點圖序列化/反序列化 |

## BRNode 生命週期

1. 建構時宣告 `InputPort` / `OutputPort`
2. 輸入值變更 → `markDirty()` 沿連線傳播到下游
3. `EvaluateScheduler.evaluateDirty()` 按拓撲順序呼叫 `evaluate()`
4. `evaluate()` 從 InputPort 讀值 → 計算 → 寫入 OutputPort

## 節點分類

| 分類 | 節點數 | 說明 | 文件連結 |
|------|--------|------|----------|
| 材料 (material) | 35+ | 基礎材料、混合、操作、形狀、視覺化 | [L3-material-nodes](L3-material-nodes.md) |
| 物理 (physics) | 17+ | 荷載、求解器、結果、崩塌 | [L3-physics-nodes](L3-physics-nodes.md) |
| 渲染 (render) | 57+ | 光照、LOD、管線、後處理、水體、天氣 | [L3-render-nodes](L3-render-nodes.md) |
| 工具 (tool) | 22+ | 輸入裝置、放置、選取、UI 配置 | [L3-tool-nodes](L3-tool-nodes.md) |
| 輸出 (output) | 8 | 匯出、效能監測 | [L3-output-nodes](L3-output-nodes.md) |

## 相關子系統

- [畫布介面](L3-canvas.md) — `NodeCanvasScreen` 與畫布元件
- [綁定系統](L3-binding.md) — `IBinder` 將節點值推送到 runtime
- [風格節點](L3-style-nodes.md) — 電影級後處理特效（膠卷顆粒、色差、暗角、像素藝術等）
