---
id: "pfsf:PFSFResultProcessor"
type: pfsf
tags: ["pfsf", "post-process", "stress", "extraction"]
---

# 📄 PFSFResultProcessor — 結果後處理

## 📖 內容
從 GPU buffer 讀回應力場、phi 場、殘差，轉換為 Java 端的 StressField、StructureResult 等結構。包含非同步 readback 與 CPU 端峰值搜尋。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFResultProcessor.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFStressExtractor.java`

## 🔗 Related Notes
- [[PFSFResultProcessor]]
- [[PFSFStressExtractor]]
- [[Result]]
- [[StressField]]
- [[StructureResult]]
- [[read]]
