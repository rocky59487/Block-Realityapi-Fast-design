---
id: "java_test:com.blockreality.api.physics.ResultApplicatorTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.physics.ResultApplicatorTest

> [!info] 摘要
> ResultApplicator 單元測試 — 驗證重試邏輯、失敗追蹤、並發安全。  注意：applyStructureResult() 等方法需要 ServerLevel（Minecraft ClassLoader）， 此處僅測試可純 JUnit 運行的公開 API： 1. StressRetryEntry 封裝行為 2. failedPositions 追蹤 API (hasPendingFailures / getFailedPositions / clearFailedPositions) 3. ApplyReport record 行為 4. volatile 欄位的 visibility（概念驗證）

## 🔗 Related
- [[ApplyReport]]
- [[Entry]]
- [[Result]]
- [[ResultApplicator]]
- [[ResultApplicatorTest]]
- [[StressRetryEntry]]
- [[StructureResult]]
- [[apply]]
- [[applyStructureResult]]
- [[clear]]
- [[clearFailedPositions]]
- [[com.blockreality.api.physics.Result]]
- [[com.blockreality.api.physics.ResultApplicator]]
- [[fail]]
- [[failed]]
- [[getFailedPositions]]
- [[hasPending]]
- [[hasPendingFailures]]
- [[record]]
