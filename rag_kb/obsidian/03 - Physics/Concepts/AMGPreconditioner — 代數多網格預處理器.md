---
id: "pfsf:AMGPreconditioner"
type: pfsf
tags: ["pfsf", "amg", "multigrid", "solver", "gpu"]
---

# 📄 AMGPreconditioner — 代數多網格預處理器

## 📖 內容
代數多網格（Algebraic Multi-Grid）預處理器，用於加速 PCG 收斂。與 PFSFMultigridBuffers、PFSFVCycleRecorder 協同。V-Cycle 的 restriction/prolongation 在 compute shader 中執行。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/AMGPreconditioner.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFMultigridBuffers.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFVCycleRecorder.java`

## 🔗 Related Notes
- [[AMGPreconditioner]]
- [[PFSFMultigridBuffers]]
- [[PFSFVCycleRecorder]]
- [[compute]]
