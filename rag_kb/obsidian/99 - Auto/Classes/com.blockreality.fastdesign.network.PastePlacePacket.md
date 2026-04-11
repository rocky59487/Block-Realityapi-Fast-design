---
id: "java_fd:com.blockreality.fastdesign.network.PastePlacePacket"
type: class
tags: ["java", "fastdesign", "network"]
---

# 🧩 com.blockreality.fastdesign.network.PastePlacePacket

> [!info] 摘要
> C→S 封包：玩家在預覽位置右鍵放置 paste（Axiom/SimpleBuilding 風格交互）。  行為： 1. Client 端 GhostPreviewRenderer 追蹤玩家準心 → 計算放置位置 2. 玩家右鍵 → 發送此封包（攜帶目標 BlockPos） 3. Server 端讀取 pendingPaste 藍圖，在目標位置放置 4. 放置成功後發送 PastePreviewSyncPacket(active=false) 清除預覽  與舊 PASTE_CONFIRM 的區別： - PASTE_CONFIRM 在原始 origin 位置放置 - PastePlacePacket 在玩家準心指向的位置放置（即 GhostPreview 的當前位置）

## 🔗 Related
- [[BlockPos]]
- [[GhostPreviewRenderer]]
- [[PastePlacePacket]]
- [[PastePreviewSyncPacket]]
- [[paste]]
