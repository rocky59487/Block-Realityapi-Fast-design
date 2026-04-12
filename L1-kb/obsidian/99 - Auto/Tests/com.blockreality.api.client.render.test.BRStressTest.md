---
id: "java_test:com.blockreality.api.client.render.test.BRStressTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.client.render.test.BRStressTest

> [!info] 摘要
> Block Reality 全管線壓力測試 — Phase 13。  測試場景： 1. 全 Composite chain 連續 100 幀空跑（無幾何，純 shader 路徑驗證） 2. FBO ping-pong 穩定性（連續 swap 1000 次不會 ID 漂移） 3. Shader bind/unbind 循環（40 支 shader 各 100 次無 GL error） 4. 天氣狀態機全狀態切換（CLEAR→RAIN→SNOW→STORM→AURORA→CLEAR） 5. LOD 品質降級/升級循環（BRShaderLOD 狀態機驗證） 6. 非同步任務排程壓力（16 個 fence sync 同時掛起） 7. 遮蔽查詢飽和測試（512 queries 全部發出 + 回讀） 8. GPU Profiler 雙緩衝切換穩定性  每項測試回傳通過/失敗 + 耗時 + 詳細錯誤。

## 🔗 Related
- [[BRShaderLOD]]
- [[BRStressTest]]
- [[bind]]
- [[render]]
- [[swap]]
- [[test]]
- [[unbind]]
