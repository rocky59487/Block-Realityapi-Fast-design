---
id: "java_api:com.blockreality.api.network.FluidEntry"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.FluidEntry

> [!info] 摘要
> 流體同步封包 (S→C) — 同步流體體積和類型變化到客戶端。  <p>每 {@code FluidConstants.SYNC_INTERVAL_TICKS} (10 ticks = 0.5s) 發送一次， 僅包含自上次同步後有變化的體素。  <p>客戶端收到後更新本地的渲染用流體狀態，驅動 WaterSurfaceNode 等。

## 🔗 Related
- [[Entry]]
- [[FluidConstants]]
- [[FluidEntry]]
- [[WaterSurfaceNode]]
- [[tick]]
