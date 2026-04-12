---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFEngine"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFEngine

> [!info] 摘要
> PFSF 引擎 — Static Facade（v0.2a + BIFROST）。  <p>保留原有 static API 以向下相容所有呼叫者（ServerTickHandler、 BlockRealityMod、BrCommand 等），內部委託給 {@link PFSFEngineInstance} singleton。</p>  <p>BIFROST 擴展：{@link HybridPhysicsRouter} 根據結構形態路由至 PFSF（規則）或 FNO ML 後端（異形）。</p>  @see IPFSFRuntime @see HybridPhysicsRouter

## 🔗 Related
- [[BlockRealityMod]]
- [[BrCommand]]
- [[HybridPhysicsRouter]]
- [[IPFSFRuntime]]
- [[PFSFEngine]]
- [[PFSFEngineInstance]]
- [[ServerTickHandler]]
