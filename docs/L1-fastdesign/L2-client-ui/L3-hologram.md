# 全息預覽渲染

> 所屬：L1-fastdesign > L2-client-ui

## 概述

提供藍圖全息投影與體素級幽靈預覽兩套渲染系統，分別用於靜態藍圖展示和動態放置預覽。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `HologramRenderer` | `client.HologramRenderer` | 藍圖全息投影渲染器，渲染半透明幽靈方塊 |
| `GhostPreviewRenderer` | `client.GhostPreviewRenderer` | 體素級動態預覽渲染器，跟隨準心即時更新 |
| `HologramState` | `client.HologramState` | 全息投影全域狀態管理 |

## 核心方法

### HologramRenderer

#### `onRenderLevelStage(RenderLevelStageEvent)`
- **觸發時機**: `AFTER_TRANSLUCENT_BLOCKS` 階段
- **說明**: 遍歷 `HologramState` 中的 Blueprint 方塊，對每個非空氣方塊渲染半透明幽靈外殼。透過距離剔除（`getCullDistanceSq()`）跳過遠處方塊。
- **渲染參數**: 顏色 RGB(80,160,255)，Alpha 由 `FastDesignConfig.getHologramGhostAlpha()` 控制，內縮 0.002f 避免 Z-fighting。

### GhostPreviewRenderer

#### `setPreview(Map<BlockPos, BlockState>, BlockPos)`
- **參數**: `blocks` — 相對座標到方塊狀態的映射；`origin` — 世界座標原點
- **說明**: 設定預覽資料，通常由 `PastePreviewSyncPacket` 呼叫

#### `onClientTick(TickEvent.ClientTickEvent)`
- **說明**: 每 tick 透過 raycast 追蹤準心位置，更新 `currentPlaceOrigin`。當偵測到右鍵按下時，發送 `PastePlacePacket` 到伺服器確認放置。
- **前提**: 玩家必須手持 `FdWandItem`

#### `onRenderLevel(RenderLevelStageEvent)`
- **說明**: 渲染幽靈方塊預覽。超過 `MAX_PREVIEW_BLOCKS`（100,000）時自動降級為線框模式（僅渲染 AABB 外框）。使用 `MapColor` 取色，Alpha 固定 0.4。

## 效能策略

- 使用 `ConcurrentHashMap` 快取預覽資料，支援跨執行緒安全讀寫
- 距離剔除減少遠處方塊渲染開銷
- 超大結構自動降級為線框模式

## 關聯接口
- 依賴 → [FdNetwork](../L2-network/L3-packets.md)（PastePlacePacket）
- 依賴 → `HologramState`（藍圖資料來源）
- 被依賴 ← [ControlPanelScreen](L3-screens.md)（全息切換按鈕）
