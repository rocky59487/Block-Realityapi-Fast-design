# 工具節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

工具節點（Category D）用於配置輸入裝置、放置行為、選取邏輯及 UI 外觀，共 22+ 個節點。

## 輸入裝置 (input/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `KeyBindingsNode` | tool.input.KeyBindings | 快捷鍵配置 |
| `MouseConfigNode` | tool.input.MouseConfig | 滑鼠設定 |
| `GamepadConfigNode` | tool.input.GamepadConfig | 遊戲手柄設定 |
| `GestureConfigNode` | tool.input.GestureConfig | 手勢配置 |

## 放置 (placement/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `BuildModeNode` | tool.placement.BuildMode | 建築模式選擇 |
| `BlueprintPlaceNode` | tool.placement.BlueprintPlace | 藍圖放置（對齊、旋轉、鏡像） |
| `GhostBlockNode` | tool.placement.GhostBlock | 幽靈方塊預覽配置 |
| `QuickPlacerNode` | tool.placement.QuickPlacer | 快速放置 |
| `BatchOpNode` | tool.placement.BatchOp | 批量操作 |

### BlueprintPlaceNode 詳細
- **輸入**: snapMode(ENUM: Grid), rotation(0~3), mirror(BOOL), ghostAlpha(0~1)
- **輸出**: previewSpec(STRUCT), ghostBlockAlpha(FLOAT)

## 選取 (selection/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `SelectionConfigNode` | tool.selection.SelectionConfig | 選取配置 |
| `SelectionFilterNode` | tool.selection.SelectionFilter | 選取過濾器 |
| `SelectionExportNode` | tool.selection.SelectionExport | 選取匯出 |
| `SelectionVizNode` | tool.selection.SelectionViz | 選取視覺化 |
| `BrushConfigNode` | tool.selection.BrushConfig | 筆刷配置 |
| `CompoundPredicateNode` | tool.selection.CompoundPredicate | 複合條件篩選 |
| `ToolMaskNode` | tool.selection.ToolMask | 工具遮罩 |

## UI 配置 (ui/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `HUDLayoutNode` | tool.ui.HUDLayout | HUD 佈局 |
| `CrosshairNode` | tool.ui.Crosshair | 準心配置 |
| `HologramStyleNode` | tool.ui.HologramStyle | 全息投影樣式 |
| `RadialMenuNode` | tool.ui.RadialMenu | 輻射選單配置 |
| `ThemeColorNode` | tool.ui.ThemeColor | 主題色彩 |
| `FontConfigNode` | tool.ui.FontConfig | 字型配置 |

## 關聯接口
- 被依賴 ← [FastDesignConfigBinder](L3-binding.md)（推送工具/UI 配置到 `FastDesignConfig`）
- 依賴 → API 層 `PlayerSelectionManager`、`Blueprint`
