---
id: "java_fd:com.blockreality.fastdesign.client.GhostPreviewRenderer"
type: class
tags: ["java", "fastdesign", "client", "client-only"]
---

# 🧩 com.blockreality.fastdesign.client.GhostPreviewRenderer

> [!info] 摘要
> 體素級全息預覽渲染器 — Fast Design v2.0  ★ v4 spec §6.2 (Axiom) + §7.2 (SimpleBuilding) 交互模式： - 預覽跟隨玩家準心（raycast 到方塊面 → 在該面偏移放置） - 右鍵直接放置（發送 PastePlacePacket），不需面板確認 - 按 ESC 或切換工具取消預覽  渲染流程： 1. 每 tick 更新預覽位置（raycast → 面偏移） 2. 在 AFTER_TRANSLUCENT_BLOCKS 渲染幽靈方塊 3. 監聽右鍵發送放置封包  效能策略： - 使用預編譯的方塊頂點資料快取 (Map<BlockPos, BlockState>) - 超過 MAX_PREVIEW_BLOCKS 時自動降級為線框模式

## 🔗 Related
- [[BlockPos]]
- [[GhostPreviewRenderer]]
- [[PastePlacePacket]]
- [[State]]
- [[tick]]
