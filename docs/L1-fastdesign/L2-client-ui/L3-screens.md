# GUI 畫面

> 所屬：L1-fastdesign > L2-client-ui

## 概述

Fast Design 提供四個主要 GUI 畫面：CAD 三視角介面、雕刻刀工具選單、控制面板和輻射輪盤。所有畫面均設定 `isPauseScreen() = false`。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `FastDesignScreen` | `client.FastDesignScreen` | 三視角 CAD 介面，含正交投影與拖曳選取 |
| `ChiselToolScreen` | `client.ChiselToolScreen` | 雕刻刀形狀選單（17 種形狀） |
| `ControlPanelScreen` | `client.ControlPanelScreen` | 400x310 主控面板，四大區塊操作 |
| `PieMenuScreen` | `client.PieMenuScreen` | 輻射狀快捷輪盤（8 個扇區） |

## FastDesignScreen

三視角 CAD 介面，左側 60% 為正交投影面板，右側 40% 為藍圖資訊面板，底部為工具列。

### 核心方法

#### `FastDesignScreen(Blueprint)`
- **參數**: `blueprint` — 要檢視的藍圖
- **說明**: 初始化 CAD 介面，解析藍圖方塊清單

#### `renderOrthoProjection(GuiGraphics)`
- **說明**: 根據 `OrthoMode`（TOP/FRONT/SIDE）渲染正交投影網格與方塊色塊

#### `resolveBlockSelection()`
- **說明**: 將螢幕空間拖曳框反算回正交網格座標，篩選落入範圍的方塊

### 快捷鍵
Tab=切換視角、Z=Undo、C=Copy、V=Paste、E=Export

## ChiselToolScreen

320x240 居中面板，長按 Alt 彈出。顯示 17 種 `SubBlockShape` 的按鈕網格（4 列排列）。

### 支援形狀
完整、半磚上/下、圓柱、四方向階梯、四方向四分之一塊、拱底/拱頂、兩方向樑、自訂

### `selectShape(SubBlockShape)`
- **說明**: 透過 `ChiselControlPacket` 將選擇發送到伺服器

## ControlPanelScreen

400x310 主控面板，分四個區塊：

| 區塊 | 內容 |
|------|------|
| 建築操作 | 實心方塊、空心牆壁、拱門、斜撐、樓板、鋼筋網 |
| 材質選擇 | 混凝土、鋼筋、鋼材、木材、自訂（含方塊 ID 輸入框） |
| 編輯工具 | 複製、粘貼預覽、鏡像 X、旋轉 90 度、填充、替換、清除、還原、確認/取消放置 |
| 進階功能 | 儲存/載入藍圖（含名稱輸入框）、NURBS 匯出、全息切換、CAD 檢視 |

所有操作透過 `FdActionPacket` 發送到伺服器端。

## PieMenuScreen

DOOM Eternal 風格的輻射輪盤，按住 Alt 呼出，放開確認。

### 8 個操作扇區
複製、貼上、填充、替換、撤銷、重做、旋轉、取消選取

### 操作邏輯
- 滑鼠方向對應扇區（0 度=上方，順時針）
- 內圓 40px 為死區，外圓 110px
- 釋放 Alt 或滑鼠鍵時執行選中操作

## 關聯接口
- 依賴 → [FdNetwork / FdActionPacket](../L2-network/L3-packets.md)
- 依賴 → `ControlPanelState`、`ClientSelectionHolder`
- 被依賴 ← [FdCommandRegistry](../L2-command/L3-fd-commands.md)（`/fd cad` 開啟 CAD）
