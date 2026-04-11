---
id: "rule:sigmaMax-normalization"
type: rule
tags: ["rule", "pfsf", "gpu", "shader", "critical"]
---

# 📄 sigmaMax 正規化約定（PFSF 核心）

## 📖 內容
PFSFDataBuilder 上傳 GPU 前會除以 sigmaMax。任何新增的 threshold buffer 必須同步處理：conductivity[i] /= sigmaMax；source[i] /= sigmaMax；maxPhi[i] /= sigmaMax（懸臂閾值）；rcomp[i] /= sigmaMax（壓碎閾值）；rtens[i] /= sigmaMax（拉斷閾值）。phi 場不變（A·φ=b 兩邊同除自動抵消）。

> [!warning] 注意
> 任一 threshold buffer 忘記除 sigmaMax 會導致 GPU 內數值尺度錯誤，收斂異常

> [!tip] 相關資訊
> ⚠️ **WARNING**: 任一 threshold buffer 忘記除 sigmaMax 會導致 GPU 內數值尺度錯誤，收斂異常
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java`

## 🔗 Related Notes
- [[Builder]]
- [[PFSFDataBuilder]]
- [[rcomp]]
- [[rtens]]
- [[sigma]]
