---
id: "java_test:com.blockreality.api.client.render.test.BRMemoryLeakScanner"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.client.render.test.BRMemoryLeakScanner

> [!info] 摘要
> Block Reality GL 資源洩漏掃描器 — Phase 13。  掃描策略： 1. 快照比對法：記錄管線 init 後的 GL 資源基線， 執行 N 幀後再次掃描，比對差異。 2. FBO 生命週期追蹤：驗證所有 FBO 在 resize 後正確釋放舊資源。 3. Shader 孤兒偵測：確認每支 shader 都有對應 program 綁定。 4. Texture 洩漏偵測：掃描已知紋理 ID 是否仍為有效 GL texture。 5. VBO/VAO 洩漏偵測：掃描已知 buffer/array 是否仍有效。 6. Query 物件洩漏：驗證 OcclusionCuller + GPUProfiler 的 query 池完整性。 7. Fence Sync 洩漏：驗證 AsyncCompute 的 fence 池。

## 🔗 Related
- [[BRMemoryLeakScanner]]
- [[init]]
- [[query]]
- [[render]]
- [[resize]]
- [[size]]
- [[test]]
