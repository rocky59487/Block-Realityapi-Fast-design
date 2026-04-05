# 施工 HUD 疊層

> 所屬：L1-fastdesign > L2-client-ui

## 概述

`ConstructionHudOverlay` 在螢幕右上角渲染施工區域的即時狀態資訊，包括工序名稱、進度條、節點應力等級與養護進度。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ConstructionHudOverlay` | `client.ConstructionHudOverlay` | Forge 事件訂閱的 HUD 疊層渲染器 |

## 核心方法

### `setZoneInfo(String phaseName, String phaseZh, float progress)`
- **參數**: `phaseName` — 工序英文名；`phaseZh` — 工序中文名；`progress` — 0.0~1.0
- **說明**: 由同步封包呼叫，設定當前施工區域資訊

### `setNodeInfo(String status, float stress, float curing, int connected)`
- **參數**: `status` — 節點類型（`RC_NODE` / `ANCHOR_PILE`）；`stress` — 應力等級 0.0~1.5；`curing` — 養護進度 0.0~1.0；`connected` — 相鄰節點數
- **說明**: 設定 RC 節點或錨樁的狀態資訊

### `clearZoneInfo()`
- **說明**: 清除所有施工區域及節點狀態資訊

### `onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post)`
- **說明**: Forge GUI 疊層渲染事件回呼。當有施工區域或節點狀態時，渲染 210px 寬的資訊面板

## 顯示內容

| 區塊 | 顯示項目 |
|------|---------|
| 施工區域 | 工序名稱、進度條（綠色填充） |
| 節點狀態 | 節點類型圖示、連接數、應力等級（安全/警告/危險）、養護進度條 |

## 視覺特效

- **平滑動畫**: 進度條與應力值使用每幀線性插值（lerp speed = 5.0/秒）
- **應力邊框色彩**: 綠色(< 0.3) → 黃色(< 0.7) → 紅色閃爍(>= 0.7)
- **養護進度條**: 橙色(< 50%) → 藍色(>= 50%)，完成顯示綠色勾號

## 關聯接口
- 依賴 → API 層 `ConstructionZone`（狀態來源）
- 被依賴 ← [HologramSyncPacket](../L2-network/L3-packets.md)（同步封包更新資料）
