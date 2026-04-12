---
id: "java_api:com.blockreality.api.physics.RWorldSnapshot"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.RWorldSnapshot

> [!info] 摘要
> 唯讀的世界快照容器。 使用 1D 陣列扁平化 3D 空間，保證 cache-friendly 存取。  索引公式: lx + sizeX  (ly + sizeY  lz) → Y 軸連續排列，物理引擎沿柱狀 (column) 掃描時 cache 命中率最高。  不持有任何 net.minecraft.world.level.Level 參照。  v3fix §4.2: 支援 changedPositions 以追蹤快照內已修改的位置。

## 🔗 Related
- [[RWorldSnapshot]]
- [[Snapshot]]
- [[size]]
- [[sizeX]]
- [[sizeY]]
