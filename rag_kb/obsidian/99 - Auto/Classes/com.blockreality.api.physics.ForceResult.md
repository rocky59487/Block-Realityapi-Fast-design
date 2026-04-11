---
id: "java_api:com.blockreality.api.physics.ForceResult"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.ForceResult

> [!info] 摘要
> 水平 4 方向（不含 UP/DOWN）— 供 distributeLoad 側方支撐遍歷使用。 ★ new-fix N3: 原先 distributeLoad 每次呼叫建立匿名 new int[][]{{...}}， 在熱路徑（每 tick 每個非錨點節點各呼叫一次）造成無謂 heap 分配。 改為 static final 常數，零分配。 原 NEIGHBOR_DIRS（6方向）移除，改為更精確命名的 HORIZONTAL_DIRS（4方向）。

## 🔗 Related
- [[ForceResult]]
- [[Result]]
- [[distributeLoad]]
- [[tick]]
