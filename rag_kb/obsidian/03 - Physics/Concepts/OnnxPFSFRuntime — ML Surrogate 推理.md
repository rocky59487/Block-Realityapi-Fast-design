---
id: "pfsf:OnnxPFSFRuntime"
type: pfsf
tags: ["pfsf", "ml", "onnx", "surrogate", "inference"]
---

# 📄 OnnxPFSFRuntime — ML Surrogate 推理

## 📖 內容
使用 ONNX Runtime 執行 PFSF surrogate 模型的輕量級推理器。輸出 channel 9 為 phi（物理尺度），進入 failure_scan 前必須除以 sigmaMax。由 HybridPhysicsRouter 根據認知 LOD 決定是否走 ONNX 快速路徑或完整 GPU PFSF。

> [!warning] 注意
> phi 輸出必須除以 sigmaMax 後才進入 failure_scan

> [!tip] 相關資訊
> ⚠️ **WARNING**: phi 輸出必須除以 sigmaMax 後才進入 failure_scan
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/HybridPhysicsRouter.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/CognitiveLODManager.java`

## 🔗 Related Notes
- [[CognitiveLODManager]]
- [[HybridPhysicsRouter]]
- [[OnnxPFSFRuntime]]
- [[fail]]
- [[sigma]]
