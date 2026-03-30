# 客戶端 UI 總覽

> 所屬：L1-fastdesign > L2-client-ui

## 概述

Fast Design 的客戶端 UI 層提供全息預覽渲染、CAD 三視角介面、控制面板、雕刻刀選單、輻射輪盤以及施工 HUD 疊層。所有類別標記 `@OnlyIn(Dist.CLIENT)`。

## 子系統

| 子系統 | 關鍵類別 | 說明 | 文件連結 |
|--------|---------|------|----------|
| 全息渲染 | `HologramRenderer`, `GhostPreviewRenderer` | 藍圖投影與體素級預覽 | [L3-hologram](L3-hologram.md) |
| GUI 畫面 | `FastDesignScreen`, `ChiselToolScreen`, `ControlPanelScreen`, `PieMenuScreen` | CAD 介面與控制面板 | [L3-screens](L3-screens.md) |
| HUD 疊層 | `ConstructionHudOverlay` | 施工區域即時狀態顯示 | [L3-hud](L3-hud.md) |

## 輔助類別

| 類別 | 說明 |
|------|------|
| `HologramState` | 全息投影全域狀態（啟用/關閉、Blueprint、位置偏移） |
| `ControlPanelState` | 控制面板材質選擇與 payload 編碼 |
| `ClientSelectionHolder` | 客戶端選取區域快取（由同步封包更新） |
| `FdKeyBindings` | 快捷鍵註冊（G=面板, Alt=輪盤） |
| `Preview3DRenderer` | CAD 模式 3D 渲染輔助 |
| `SelectionOverlayRenderer` | 選取框線框渲染 |
| `TransformGizmoRenderer` | 變換 Gizmo 渲染 |
| `ChiselMeshBuilder` | 雕刻形狀網格構建 |
| `WandClientHandler` | FdWand 客戶端互動處理 |
