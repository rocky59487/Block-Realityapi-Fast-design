---
id: "trouble:pfsf-divergence"
type: troubleshooting
tags: ["troubleshooting", "pfsf", "gpu", "divergence", "physics"]
severity: "critical"
---

# 🚑 PFSF GPU 求解發散或收斂極慢

> [!bug] 可能原因
> 1. 某個 threshold buffer 忘記除以 sigmaMax，導致數值尺度錯誤。2. Jacobi/RBGS/PCG matvec shader 的 26 連通 stencil 不一致（面/邊/角權重不同）。3. hField 被多個 shader 同時寫入，造成 race condition。4. conductivity 或 source 出現 NaN/Inf。排查：啟用 PFSFPCGRecorder 或 PFSFVCycleRecorder 紀錄殘差曲線，確認哪一層發散。

> [!danger] Severity: `critical`

> [!tip] 相關資訊
> 🚨 Severity: `critical`
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFPCGRecorder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFVCycleRecorder.java`

## 🔗 Related Notes
- [[PFSFDataBuilder]]
- [[PFSFPCGRecorder]]
- [[PFSFVCycleRecorder]]
- [[sigma]]
