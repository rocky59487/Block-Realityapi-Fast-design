---
id: "test:PFSFDataBuilderTest"
type: test_coverage
tags: ["test", "pfsf", "data-builder", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFDataBuilderTest'"
---

# 🧪 PFSFDataBuilderTest — PFSF 資料建構器測試

## 📝 適用場景
測試 PFSFDataBuilder 計算 conductivity、source、threshold 的正確性，以及 sigmaMax 正規化是否符合預期。修改 PFSFDataBuilder、PFSFSourceBuilder 或新增 threshold buffer 時必須執行。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFDataBuilderTest'`
> 📎 Related Source:
>   - [[PFSFDataBuilder]]
>   - [[PFSFSourceBuilder]]

## 🔗 Related Notes
- [[Builder]]
- [[PFSFDataBuilder]]
- [[PFSFDataBuilderTest]]
- [[PFSFSourceBuilder]]
- [[sigma]]
