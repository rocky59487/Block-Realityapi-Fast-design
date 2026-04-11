---
id: "keyclass:OnnxPFSFRuntime"
type: key_class
tags: ["key-class", "pfsf", "ml", "onnx"]
fqn: "com.blockreality.api.physics.pfsf.OnnxPFSFRuntime"
thread_safety: "avoid concurrent OrtSession inference"
---

# 🧩 OnnxPFSFRuntime 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.pfsf.OnnxPFSFRuntime`
> **Thread Safety**: avoid concurrent OrtSession inference

## 📋 職責
載入並執行 PFSF surrogate ONNX 模型，為小型或遠距離結構島提供快速物理推理。線程安全：OrtSession 執行推論時需避免並發衝突，通常由單一執行緒調度。主要方法：infer(float[] input)、loadModel(String path)。修改注意：輸出 channel 9（phi）為物理尺度，進入 failure_scan 前必須除以 sigmaMax。

> [!warning] WARNING
> phi 輸出必須除以 sigmaMax 後才進入 failure_scan

> [!tip] Metadata
> ⚠️ **WARNING**: phi 輸出必須除以 sigmaMax 後才進入 failure_scan

## 🔗 Related Notes
- [[OnnxPFSFRuntime]]
- [[String]]
- [[fail]]
- [[infer]]
- [[load]]
- [[loadModel]]
- [[ring]]
- [[sigma]]
