---
id: "java_api:com.blockreality.api.spi.ICuringManager"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.ICuringManager

> [!info] 摘要
> Per-block curing management — tracks concrete curing progress. Construction Intern module will provide full implementation.  Responsibilities: - Track which blocks are currently curing - Maintain curing progress (0.0-1.0) for each block - Determine when curing is complete - Periodic tick to advance curing time  Thread-safe for concurrent access from game and physics threads.  @since 1.0.0

## 🔗 Related
- [[ICuringManager]]
- [[Thread]]
- [[advance]]
- [[from]]
- [[full]]
- [[read]]
- [[ring]]
- [[safe]]
- [[tick]]
