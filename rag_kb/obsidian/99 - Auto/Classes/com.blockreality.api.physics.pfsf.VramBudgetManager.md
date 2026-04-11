---
id: "java_api:com.blockreality.api.physics.pfsf.VramBudgetManager"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.VramBudgetManager

> [!info] 摘要
> VRAM 智慧預算管理器 — 自動偵測 GPU 顯存容量，按比例分配。  <h2>設計動機</h2> 舊版硬編碼 768MB 預算，在 2GB 小卡上浪費/在 24GB 大卡上太保守。 現改為自動偵測 + 使用者可調比例（預設 60%）。  <h2>分區架構</h2> <pre> PFSF   66.7%  — 結構引擎（最大消費者） Fluid  20.8%  — 流體引擎 Other  12.5%  — Thermal/Wind/EM </pre>  <h2>執行緒安全</h2> 所有計數器使用 AtomicLong，多線程並行分配安全。 tryRecord/recordFree 保證不漏記（CRITICAL fix: 舊版 freeBuffer 未遞減計數器）。

## 🔗 Related
- [[AtomicLong]]
- [[VramBudgetManager]]
- [[free]]
- [[freeBuffer]]
- [[record]]
- [[recordFree]]
- [[tryRecord]]
