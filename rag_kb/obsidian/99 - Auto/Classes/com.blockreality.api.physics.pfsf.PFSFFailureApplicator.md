---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFFailureApplicator"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFFailureApplicator

> [!info] 摘要
> PFSF 斷裂結果應用器 — 讀取 GPU fail_flags[]，轉換為 FailureType，觸發 CollapseManager。  <p>安全機制：</p> <ul> <li>MAX_FAILURE_PER_TICK 限制每 tick 最大斷裂數</li> <li>MAX_CASCADE_RADIUS 限制單次蔓延半徑</li> <li>保守重啟 Chebyshev（有新斷裂時）</li> </ul>  參考：PFSF 手冊 §5.5, §6.1

## 🔗 Related
- [[CollapseManager]]
- [[FailureType]]
- [[PFSFFailureApplicator]]
- [[Type]]
- [[fail]]
- [[tick]]
