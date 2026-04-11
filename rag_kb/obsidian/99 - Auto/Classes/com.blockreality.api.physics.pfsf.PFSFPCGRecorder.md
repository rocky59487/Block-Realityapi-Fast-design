---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFPCGRecorder"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFPCGRecorder

> [!info] 摘要
> PCG (Preconditioned Conjugate Gradient) GPU 求解器。  <p>用於低頻殘差收斂：RBGS 消除高頻噪聲後，PCG 快速收斂全域模式。</p>  <p>v2: 實作 Jacobi 預條件（對角線 M = diag(A₂₆)），z = M⁻¹r 即時計算。 預條件降低條件數 κ → 加速收斂 O(√κ) → O(√(κ/κ_diag))。</p>  <p>GPU 向量（額外 3 個 buffer per island，z 即時計算無需額外 buffer）：</p> <pre> r[N]  — 殘差向量 p[N]  — 搜索方向 Ap[N] — 矩陣-向量乘積 </pre>  <p>PCG 迭代步驟（每步 4 個 GPU dispatch）：</p> <ol> <li>Ap = A₂₆  p                    (26-connect

## 🔗 Related
- [[PFSFPCGRecorder]]
- [[connect]]
- [[dispatch]]
