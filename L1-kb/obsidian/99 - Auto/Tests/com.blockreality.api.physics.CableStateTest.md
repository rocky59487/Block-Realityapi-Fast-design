---
id: "java_test:com.blockreality.api.physics.CableStateTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.physics.CableStateTest

> [!info] 摘要
> CableState 單元測試 — 驗證 XPBD 纜索模擬狀態的核心行為。  測試策略： 1. 建構子：節點數計算（短/長纜索）、restSegmentLength 2. resetLambdas()：歸零驗證 3. calculateTension()：鬆弛/拉伸場景 4. isBroken()：閾值判定 5. nodes unmodifiable 防禦性驗證 6. nodeCount() 一致性  參考：Macklin & Müller MIG'16 — per-constraint λ, XPBD iterations

## 🔗 Related
- [[CableState]]
- [[CableStateTest]]
- [[State]]
- [[calculate]]
- [[calculateTension]]
- [[com.blockreality.api.physics.CableState]]
- [[isBroken]]
- [[nodeCount]]
- [[reset]]
- [[resetLambdas]]
- [[strain]]
