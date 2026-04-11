---
id: "java_api:com.blockreality.api.network.ChiselControlPacket"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.ChiselControlPacket

> [!info] 摘要
> C→S 工具控制封包 — 雕刻刀 + 建築法杖 共用。  統一操作邏輯： - SEL_WIDTH_INC / SEL_WIDTH_DEC：左右鍵調整選區寬度 (1~10) - SEL_HEIGHT_INC / SEL_HEIGHT_DEC：上下鍵調整選區高度 (1~10) - EDGE_LENGTH_INC / EDGE_LENGTH_DEC：H 鍵調整邊長（寬高同時 ±1） - ERASE_ON / ERASE_OFF：X 鍵按住/放開 → 橡皮擦模式 - SELECT_SHAPE：從選單選擇雕刻形狀（payload = shape serializedName）

## 🔗 Related
- [[ChiselControlPacket]]
- [[load]]
- [[serialize]]
- [[serializedName]]
- [[shape]]
