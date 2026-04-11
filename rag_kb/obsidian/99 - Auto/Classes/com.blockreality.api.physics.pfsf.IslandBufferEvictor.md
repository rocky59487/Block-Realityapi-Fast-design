---
id: "java_api:com.blockreality.api.physics.pfsf.IslandBufferEvictor"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.IslandBufferEvictor

> [!info] 摘要
> LRU Island Buffer 驅逐器 — VRAM 壓力大時驅逐最久未使用的 island buffer。  <h2>設計動機</h2> 大地圖中 island 數量可達數百，但同一時間只有玩家附近的 island 需要 GPU 計算。 遠處 island 的 GPU buffer 佔用 VRAM 但閒置。  <h2>驅逐策略</h2> <ol> <li>每次處理 island 時呼叫 {@link #touchIsland(int)} 更新 LRU 時戳</li> <li>每 N tick 呼叫 {@link #evictIfNeeded()}，若 VRAM 壓力 &gt; 70% 則驅逐最舊 island</li> <li>每次最多驅逐 3 個 island（避免 spike）</li> </ol>

## 🔗 Related
- [[IslandBuffer]]
- [[IslandBufferEvictor]]
- [[evictIfNeeded]]
- [[tick]]
- [[touch]]
- [[touchIsland]]
