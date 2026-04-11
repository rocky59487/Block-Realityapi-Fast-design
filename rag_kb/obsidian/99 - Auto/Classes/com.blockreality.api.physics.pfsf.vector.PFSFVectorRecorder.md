---
id: "java_api:com.blockreality.api.physics.pfsf.vector.PFSFVectorRecorder"
type: class
tags: ["java", "api", "vector"]
---

# 🧩 com.blockreality.api.physics.pfsf.vector.PFSFVectorRecorder

> [!info] 摘要
> PFSF WSS-HQR 向量場求解 dispatch 錄製器。  <p>每個 8³ macro-block 映射到一個 Workgroup（512 threads = 16 warps）。 在高應力巨集塊（stressRatio > 0.7）上執行局部 Householder QR， 恢復精確的 3D 向量場（扭轉、複合剪切）。  <p>插入位置：RBGS Phase 1 完成後、PCG Phase 2 開始前。

## 🔗 Related
- [[PFSFVectorRecorder]]
- [[dispatch]]
- [[read]]
