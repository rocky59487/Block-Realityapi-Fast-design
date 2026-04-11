---
id: "java_api:com.blockreality.api.client.ClientStressCache"
type: class
tags: ["java", "api", "client", "client-only"]
---

# 🧩 com.blockreality.api.client.ClientStressCache

> [!info] 摘要
> 客戶端應力快取 — v3fix §1.8  存放從伺服器同步過來的應力數據，供 StressHeatmapRenderer 讀取。  線程安全設計： - ConcurrentHashMap：Netty IO 線程寫入，渲染線程讀取 - LRU 上限：MAX_CACHE_SIZE 筆（防止記憶體溢出） - 維度切換時清空（ClientStressPacketHandler 呼叫 clearCache）

## 🔗 Related
- [[ClientStressCache]]
- [[StressHeatmapRenderer]]
- [[clear]]
- [[clearCache]]
