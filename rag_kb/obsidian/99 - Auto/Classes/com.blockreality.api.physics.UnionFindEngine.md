---
id: "java_api:com.blockreality.api.physics.UnionFindEngine"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.UnionFindEngine

> [!info] 摘要
> BFS 連通塊引擎 — 從 Anchor 擴散，找出所有失去支撐的懸空方塊。  錨定策略（v3 — Scan Margin）： - 掃描區 = 使用者指定範圍 + margin（預設 4 格） - Anchor = 掃描區邊界上的所有非空氣方塊（有限元素邊界條件） - 崩塌區 = 僅限內部（排除 margin 的區域） → margin 給 BFS 額外空間追蹤支撐路徑 → 支撐柱在 margin 內被捕捉 → 不會誤殺合理建築 → 只有完全包在崩塌區內且不連接任何 anchor 的結構才會掉  效能設計： 1. 零 GC：BitSet (nonAir/supported) + int[] queue 2. 1D index 運算 3. 雙煞車：bfs_max_blocks (65536) + bfs_max_ms (50ms)

## 🔗 Related
- [[BitSet]]
- [[UnionFind]]
- [[UnionFindEngine]]
- [[index]]
- [[nonAir]]
- [[queue]]
