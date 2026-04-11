---
id: "java_api:com.blockreality.api.physics.pfsf.OnnxPFSFRuntime"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.OnnxPFSFRuntime

> [!info] 摘要
> ONNX-based PFSF runtime — loads a trained FNO3DMultiField model and runs inference for irregular structures.  <p>Input (5 channels): [1, L, L, L, 5] — occ, E, ν, ρ, Rcomp (normalized)</p> <p>Output (10 channels): [1, L, L, L, 10] — σ(6) + u(3) + φ(1)</p>  <p>The φ channel (index 9) is fed directly into the existing PFSF failure detection pipeline, providing FEM-quality physics at FNO inference spe

## 🔗 Related
- [[OnnxPFSFRuntime]]
- [[detect]]
- [[fail]]
- [[index]]
- [[infer]]
- [[line]]
- [[load]]
- [[normalize]]
