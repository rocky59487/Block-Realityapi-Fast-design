---
id: "pattern:new-test"
type: pattern
tags: ["pattern", "test", "junit", "howto"]
---

# 🧩 如何新增 JUnit 5 測試

## 🪜 步驟
- 1. 在 api/src/test/java/... 或 fastdesign/src/test/java/... 下建立測試類別，命名以 Test 結尾。
- 2. 使用 @Test、@BeforeEach、@ParameterizedTest 等 JUnit 5 註解。
- 3. 執行 ./gradlew :api:test --tests '完整類別名' 進行驗證。

> [!example] 範例類別: [[PFSFDataBuilderTest]]

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/test/java/com/blockreality/api/physics/pfsf/PFSFDataBuilderTest.java`
>   - `Block Reality/api/src/test/java/com/blockreality/api/physics/ForceEquilibriumSolverTest.java`

## 🔗 Related Notes
- [[ForceEquilibriumSolverTest]]
- [[PFSFDataBuilderTest]]
- [[test]]
