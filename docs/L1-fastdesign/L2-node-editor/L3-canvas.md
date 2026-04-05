# 節點畫布介面

> 所屬：L1-fastdesign > L2-node-editor

## 概述

`NodeCanvasScreen` 是 Grasshopper 風格的無限平移/縮放 2D 畫布，繼承 Minecraft `Screen`，提供節點拖曳、連線、框選、搜尋、群組建立及 Undo/Redo 功能。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `NodeCanvasScreen` | `client.node.canvas.NodeCanvasScreen` | 畫布主 Screen |
| `CanvasTransform` | `client.node.canvas.CanvasTransform` | 平移/縮放座標轉換 |
| `NodeWidgetRenderer` | `client.node.canvas.NodeWidgetRenderer` | 節點方塊渲染 |
| `WireRenderer` | `client.node.canvas.WireRenderer` | Bezier 曲線連線渲染 |
| `PortInteraction` | `client.node.canvas.PortInteraction` | 端口拖曳連線互動 |
| `BoxSelectionHandler` | `client.node.canvas.BoxSelectionHandler` | 框選矩形處理 |
| `NodeSearchPanel` | `client.node.canvas.NodeSearchPanel` | Tab/雙擊呼出的搜尋面板 |
| `NodeCanvasUndoManager` | `client.node.canvas.NodeCanvasUndoManager` | 畫布層 Undo/Redo 管理 |
| `NodeTooltipRenderer` | `client.node.canvas.NodeTooltipRenderer` | 懸停 Tooltip 渲染 |
| `InlinePreviewRenderer` | `client.node.canvas.InlinePreviewRenderer` | 節點內嵌預覽渲染 |

## 內嵌控制元件 (canvas/control/)

| 類別 | 說明 |
|------|------|
| `InlineSlider` | 數值滑桿 |
| `InlineCheckbox` | 布林勾選框 |
| `InlineDropdown` | 下拉選單 |
| `InlineColorPicker` | 色彩選擇器 |
| `InlineCurveEditor` | 曲線編輯器 |
| `InlineBlockPicker` | 方塊選擇器 |
| `InlineVoxelEditor` | 體素編輯器 |
| `InlineRecipeGrid` | 配方網格 |

## 核心方法

### `NodeCanvasScreen(NodeGraph)`
- **參數**: `graph` — 要編輯的節點圖
- **說明**: 初始化畫布、排程器及所有子系統

### `render(GuiGraphics, int, int, float)`
- **說明**: 每幀渲染流程：評估髒節點 → 背景網格 → 群組 → 連線 → 臨時連線 → 節點 → 框選矩形 → 搜尋面板 → Tooltip → HUD 資訊

### `addNodeFromSearch(String typeId, float, float)`
- **說明**: 從搜尋面板建立節點，放置於畫布座標，記錄 Undo

## 操作快捷鍵

| 快捷鍵 | 功能 |
|--------|------|
| 中鍵拖曳 | 平移畫布 |
| 滾輪 | 縮放（以滑鼠為中心） |
| 左鍵拖曳空白 | 框選 |
| 左鍵拖曳端口 | 連線 |
| 右鍵連線 | 斷開連線 |
| Tab / 雙擊空白 | 搜尋面板 |
| Ctrl+G | 建立群組 |
| Ctrl+Z / Ctrl+Y | Undo / Redo |
| Ctrl+S | 儲存節點圖至 JSON |
| Ctrl+D | 複製選中節點 |
| Ctrl+A | 全選 |
| F | 全部適配（Fit All） |
| Delete | 刪除選中 |

## 關聯接口
- 依賴 → [NodeGraph](index.md)（DAG 資料結構）
- 依賴 → [IBinder 系統](L3-binding.md)（即時預覽）
- 被依賴 ← [FastDesignScreen](../L2-client-ui/L3-screens.md)（CAD 介面入口）
