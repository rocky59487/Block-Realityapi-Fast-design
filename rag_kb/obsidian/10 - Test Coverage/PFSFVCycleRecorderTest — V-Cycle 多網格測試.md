---
id: "test:PFSFVCycleRecorderTest"
type: test_coverage
tags: ["test", "pfsf", "amg", "multigrid", "v-cycle", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFVCycleRecorderTest'"
---

# 🧪 PFSFVCycleRecorderTest — V-Cycle 多網格測試

## 📝 適用場景
測試 AMGPreconditioner 的 V-Cycle 正確性與收斂紀錄。修改 AMG shader（amg_gather_prolong、amg_scatter_restrict、mg_restrict、mg_prolong）或多網格緩衝區時必須執行。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFVCycleRecorderTest'`
> 📎 Related Source:
>   - [[AMGPreconditioner]]
>   - [[PFSFMultigridBuffers]]

## 🔗 Related Notes
- [[AMGPreconditioner]]
- [[PFSFVCycleRecorder]]
- [[PFSFVCycleRecorderTest]]
