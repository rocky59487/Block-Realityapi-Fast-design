---
id: "rule:fno-phi-normalization"
type: rule
tags: ["rule", "ml", "onnx", "pfsf"]
---

# 📄 FNO phi 正規化規則

## 📖 內容
OnnxPFSFRuntime.infer() 輸出的 phi（channel 9）為物理尺度，進入 failure_scan 前必須除以 sigmaMax。

> [!warning] 注意
> 忘記除 sigmaMax 會導致 ML 推論結果與 CPU PFSF 不一致

> [!tip] 相關資訊
> ⚠️ **WARNING**: 忘記除 sigmaMax 會導致 ML 推論結果與 CPU PFSF 不一致
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`

## 🔗 Related Notes
- [[OnnxPFSFRuntime]]
- [[fail]]
- [[infer]]
- [[sigma]]
