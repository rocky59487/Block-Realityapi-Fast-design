# 施工系統

> 所屬：[L1-fastdesign](../index.md)

## 概述

Fast Design 施工系統透過 Forge 事件攔截方塊放置行為，確保玩家在施工區域內遵循工序規範。施工區域的建立與管理由 `ConstructionCommand`（`/br_zone`）指令處理，養護 tick 邏輯由 API 層的 `ServerTickHandler` 驅動，Fast Design 層僅負責放置事件的合規性檢查。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-event-handler](L3-event-handler.md) | `ConstructionEventHandler` 施工工序事件攔截器 |
