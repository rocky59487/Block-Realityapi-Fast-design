---
id: "dataflow:network-packet-lifecycle"
type: dataflow
tags: ["dataflow", "network", "packet", "fastdesign"]
---

# 🌊 網路封包生命周期（以 FastDesign 為例）

## 📝 概述
玩家操作（如選區變更）→ WandClientHandler / ClientSelectionHolder 收集輸入 → FdSelectionSyncPacket 序列化選區資料 → FdNetwork 發送至伺服器 → 伺服器端 Packet handler 解包 → 更新 Server-side selection state → 廣播給其他玩家或觸發建築事件（ConstructionEventHandler）。關鍵：封包類別本身不能 import client-only 類別。

## 🔄 資料流階段
1. [[WandClientHandler]]
2. [[FdSelectionSyncPacket]]
3. [[FdNetwork]]
4. Server packet handler
5. [[ConstructionEventHandler]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - [[WandClientHandler]]
>   - [[FdSelectionSyncPacket]]
>   - [[FdNetwork]]
>   - Server packet handler
>   - [[ConstructionEventHandler]]
> 📎 Related Files:
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/WandClientHandler.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/FdSelectionSyncPacket.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/FdNetwork.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/construction/ConstructionEventHandler.java`

## 🔗 Related Notes
- [[ClientSelectionHolder]]
- [[ConstructionEventHandler]]
- [[FdNetwork]]
- [[FdSelectionSyncPacket]]
- [[Holder]]
- [[WandClientHandler]]
- [[handle]]
- [[onEvent]]
