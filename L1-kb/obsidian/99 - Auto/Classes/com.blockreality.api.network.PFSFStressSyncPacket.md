---
id: "java_api:com.blockreality.api.network.PFSFStressSyncPacket"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.PFSFStressSyncPacket

> [!info] 摘要
> M10: PFSF 應力場同步封包（Server → Client）。 <p> 將 GPU 計算的歸一化應力值同步給客戶端，用於： <ul> <li>應力熱力圖渲染（R 鍵 overlay）</li> <li>客戶端預測性崩塌粒子效果</li> <li>HUD 上的結構安全度指示器</li> </ul> <p> 頻寬控制： - 只同步 stress ≥ 0.3 的方塊（安全區不傳） - 每 island 每 10 tick 最多同步一次 - 最大 4096 entries/packet（~50KB）

## 🔗 Related
- [[PFSFStressSyncPacket]]
- [[StressSyncPacket]]
- [[tick]]
