---
id: "java_api:com.blockreality.api.physics.pfsf.UnionFindInt"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.UnionFindInt

> [!info] 摘要
> Int-array-based Union-Find (Disjoint Set Union) for PFSF anchor clustering.  ★ Performance fix: replaces the generic {@link UnionFind}&lt;T&gt; for integer-indexed use cases. Two int[] arrays vs two HashMap instances reduces memory usage by ~91% (no object header, no hash table overhead, no Integer autoboxing).  Supports path compression + union by rank: amortized near-O(α(n)) per operation. Capac

## 🔗 Related
- [[UnionFind]]
- [[UnionFindInt]]
- [[com.blockreality.api.physics.pfsf.UnionFind]]
- [[hash]]
- [[index]]
- [[ports]]
- [[replace]]
- [[ring]]
- [[union]]
