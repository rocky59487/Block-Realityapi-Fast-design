# ConstructionEventHandler

> 所屬：L1-fastdesign > [L2-construction](index.md)

## 概述

施工工序事件攔截器，監聽 Forge 的 `BlockEvent.EntityPlaceEvent`，在施工區域內驗證方塊放置是否符合當前工序規範。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ConstructionEventHandler` | `construction.ConstructionEventHandler` | Forge 事件訂閱者，攔截施工區域內的方塊放置 |

## 運作機制

1. 玩家放置方塊時觸發 `BlockEvent.EntityPlaceEvent`
2. 跳過客戶端事件與非玩家實體
3. 透過 `ConstructionZoneManager.get(level)` 取得施工區域管理器
4. 以 `manager.getZoneAt(pos)` 檢查放置位置是否在施工區域內
5. 若在區域內，以 `zone.canPlace(blockId)` 驗證方塊是否被當前工序允許
6. 不允許 → 取消事件並顯示禁止訊息（含當前工序名稱與允許方塊清單）
7. 允許 → 呼叫 `zone.recordBlockPlaced(pos)` 記錄放置

## 核心方法

### `onBlockPlace(BlockEvent.EntityPlaceEvent)`
- **參數**: Forge 方塊放置事件
- **回傳**: `void`（透過 `event.setCanceled(true)` 取消不合規放置）
- **說明**: 使用 `@SubscribeEvent(priority = EventPriority.LOW)` 註冊，確保在其他事件處理器之後執行。僅對 `ServerPlayer` 生效。

## 事件優先級

使用 `EventPriority.LOW`，讓其他高優先級事件處理器（如物理引擎）先執行，避免施工規範攔截干擾物理檢查。

## 註冊方式

透過 `@Mod.EventBusSubscriber` 自動註冊至 Forge 事件匯流排：
```java
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
```

## 關聯接口
- 依賴 → `ConstructionZoneManager`（API 層施工區域管理）
- 依賴 → `ConstructionZone`（API 層施工區域實體）
- 依賴 → `ConstructionPhase`（API 層工序階段定義）
- 被依賴 ← [ConstructionCommand](../L2-command/L3-fd-commands.md)（`/br_zone` 指令建立的區域由此處理器守護）
