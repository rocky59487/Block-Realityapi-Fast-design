---
id: "java_api:com.blockreality.api.physics.pfsf.ShapeClassifier"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.ShapeClassifier

> [!info] 摘要
> Classifies structure islands as "regular" or "irregular" to decide which physics backend to use:  <ul> <li>Regular (score < threshold) → PFSF iterative solver (fast, proven)</li> <li>Irregular (score >= threshold) → FNO ML surrogate (handles cantilevers, arches, etc.)</li> </ul>  <p>Irregularity score ∈ [0, 1] based on:</p> <ol> <li>Fill ratio vs AABB (empty space = irregular)</li> <li>Surface-to-

## 🔗 Related
- [[AABB]]
- [[ShapeClassifier]]
- [[decide]]
- [[empty]]
- [[handle]]
- [[score]]
- [[solve]]
