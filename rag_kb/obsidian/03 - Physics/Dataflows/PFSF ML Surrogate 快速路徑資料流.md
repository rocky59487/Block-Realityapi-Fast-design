---
id: "dataflow:pfsf-ml-shortcut"
type: dataflow
tags: ["dataflow", "pfsf", "ml", "onnx", "inference"]
---

# 🌊 PFSF ML Surrogate 快速路徑資料流

## 📝 概述
StructureIslandRegistry 認知評估 → CognitiveLODManager 決定 LOD 層級 → HybridPhysicsRouter 判斷島嶼大小/複雜度是否適合走 ONNX 快速路徑 → OnnxPFSFRuntime.infer() 執行 ONNX 推理（輸出 phi 物理尺度）→ phi 除以 sigmaMax 後進入 failure_scan → 與完整 GPU PFSF 共用後續的 ResultProcessor 與 FailureApplicator。

## 🔄 資料流階段
1. [[CognitiveLODManager]]
2. [[HybridPhysicsRouter]]
3. [[OnnxPFSFRuntime]]
4. [[failure_scan.comp.glsl]]

> [!warning] 注意
> OnnxPFSFRuntime 輸出的 phi 必須除以 sigmaMax 後才進入 failure_scan

> [!tip] 相關資訊
> ⚠️ **WARNING**: OnnxPFSFRuntime 輸出的 phi 必須除以 sigmaMax 後才進入 failure_scan
> 🔄 Pipeline Stages:
>   - [[CognitiveLODManager]]
>   - [[HybridPhysicsRouter]]
>   - [[OnnxPFSFRuntime]]
>   - [[failure_scan.comp.glsl]]
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/CognitiveLODManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/HybridPhysicsRouter.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`

## 🔗 Related Notes
- [[CognitiveLODManager]]
- [[HybridPhysicsRouter]]
- [[OnnxPFSFRuntime]]
- [[Result]]
- [[StructureIsland]]
- [[StructureIslandRegistry]]
- [[fail]]
- [[infer]]
- [[sigma]]
