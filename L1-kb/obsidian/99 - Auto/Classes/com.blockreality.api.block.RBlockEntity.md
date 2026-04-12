---
id: "java_api:com.blockreality.api.block.RBlockEntity"
type: class
tags: ["java", "api", "block"]
---

# 🧩 com.blockreality.api.block.RBlockEntity

> [!info] 摘要
> Block Reality 物理方塊實體 — 儲存材料參數與結構狀態。  架構決策 AD-1 (BlockEntity over Capability): 1. NBT 持久化：save/load 自動處理材料+應力存檔 2. Client 同步：getUpdateTag + ClientboundBlockEntityDataPacket 3. Tick 能力：未來可加養護計時 (RC curing)  效能設計： - 同步節流：50ms 間隔，避免高頻 sendBlockUpdated 壅塞網路 - 批量更新：setStressLevelBatch() 跳過逐一同步，flushSync() 統一觸發  @since 1.0.0

> [!tip] 資訊
> 🔼 Extends: BlockEntity

## 🔗 Related
- [[RBlock]]
- [[RBlockEntity]]
- [[com.blockreality.api.block.RBlock]]
- [[flush]]
- [[flushSync]]
- [[getUpdateTag]]
- [[load]]
- [[ring]]
- [[save]]
- [[setStressLevel]]
- [[setStressLevelBatch]]
