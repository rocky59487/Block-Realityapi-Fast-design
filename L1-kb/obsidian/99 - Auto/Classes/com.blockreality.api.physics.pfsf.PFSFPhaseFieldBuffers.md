---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFPhaseFieldBuffers"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFPhaseFieldBuffers

> [!info] 摘要
> PFSF 相場斷裂 Buffer — 從 PFSFIslandBuffer 提取。  <p>管理 Ambati 2015 混合相場的 GPU buffer： <ul> <li>{@code hField[N]} — 最大應變能歷史（不可逆遞增）</li> <li>{@code dField[N]} — 損傷場 d ∈ [0,1]，d>0.95 → 斷裂</li> <li>{@code hydration[N]} — 養護度 ∈ [0,1]，影響 G_c 尺度</li> </ul>  <p>v3 重構：移除 3 個冗餘 backward-compat buffer（damage/history/gc）， 改用 getter 委託。每 island 節省 12N bytes VRAM。</p>  <p>P1 重構：原本 6 個 buffer handle + 3 個 alias 散在 PFSF

## 🔗 Related
- [[IslandBuffer]]
- [[PFSFIslandBuffer]]
- [[PFSFPhaseFieldBuffers]]
- [[handle]]
