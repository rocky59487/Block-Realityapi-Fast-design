---
id: "java_api:com.blockreality.api.spi.ILoadPathManager"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.ILoadPathManager

> [!info] 摘要
> 載重傳導路徑管理介面 (Load Path Manager SPI)  Load Path Engine (LoadPathEngine) — Manages structural load transmission through block hierarchies.  核心責任 (Core Responsibilities): - 追蹤結構載重分配（每個方塊記住自己的支撐點） - 當方塊被放置或破壞時，更新載重路徑 - 級聯崩塌判定（支撐被破壞後的連鎖反應） - 診斷與追蹤支撐鏈  線程安全 (Thread Safety): Method calls are thread-safe for concurrent access from game and physics engine threads. All operations are localized to the affecte

## 🔗 Related
- [[ILoadPathManager]]
- [[LoadPathEngine]]
- [[Thread]]
- [[from]]
- [[load]]
- [[read]]
- [[safe]]
