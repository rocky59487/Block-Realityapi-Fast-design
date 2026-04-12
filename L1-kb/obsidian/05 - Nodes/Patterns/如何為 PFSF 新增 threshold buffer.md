---
id: "pattern:pfsf-threshold-buffer"
type: pattern
tags: ["pattern", "pfsf", "gpu", "buffer", "howto"]
---

# 🧩 如何為 PFSF 新增 threshold buffer

## 🪜 步驟
- 1. 在 PFSFBufferManager 或對應的 buffer 類別中分配新 buffer。
- 2. 在 PFSFDataBuilder 中計算該 buffer 的每個元素值。
- 3. **關鍵**：上傳 GPU 前，每個元素必須除以 sigmaMax 做正規化。
- 4. 在對應的 compute shader 中宣告並使用該 buffer。
- 5. 在 PFSFResultProcessor 中如需讀回，加入 readback 邏輯。

> [!tip] 相關資訊
> ⚠️ **WARNING**: 忘記 /= sigmaMax 會導致數值尺度錯誤
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFBufferManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFResultProcessor.java`

## 🔗 Related Notes
- [[BufferManager]]
- [[Builder]]
- [[PFSFBufferManager]]
- [[PFSFDataBuilder]]
- [[PFSFResultProcessor]]
- [[Result]]
- [[compute]]
- [[read]]
- [[sigma]]
