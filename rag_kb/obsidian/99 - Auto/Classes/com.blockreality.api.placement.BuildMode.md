---
id: "java_api:com.blockreality.api.placement.BuildMode"
type: class
tags: ["java", "api", "placement"]
---

# 🧩 com.blockreality.api.placement.BuildMode

> [!info] 摘要
> Build Mode — state machine for multi-block placement patterns.  Each mode defines how two (or more) anchor points translate into a set of world positions for block placement.  NORMAL   — vanilla single-block placement (pass-through) LINE     — straight line between pos1 → pos2 (Bresenham-like) WALL     — 2D rectangular plane spanned by pos1 + pos2 CUBE     — 3D filled cuboid (identical to /fd fill

## 🔗 Related
- [[BuildMode]]
- [[fill]]
- [[line]]
- [[positions]]
- [[translate]]
