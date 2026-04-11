---
id: "java_api:com.blockreality.api.physics.pfsf.ComputeRangePolicy"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.ComputeRangePolicy

> [!info] 摘要
> 動態計算範圍策略 — 根據 VRAM 壓力決定 island 處理策略。  <h2>設計動機</h2> VRAM 接近滿載時，需要優雅降級而非直接拒絕分配： <ul> <li>壓力 &lt; 50%：全量處理（L0 full resolution）</li> <li>壓力 50-70%：減少迭代步數</li> <li>壓力 70-85%：僅分配粗網格（L1 coarse only）</li> <li>壓力 &gt; 85%：拒絕新 island</li> </ul>  <h2>使用位置</h2> <ol> <li>{@code PFSFBufferManager.getOrCreateBuffer()} — 決定是否分配 + 分配精度</li> <li>{@code PFSFEngineInstance.onServerTick()} — 動態調整迭代步數</li> </ol>

## 🔗 Related
- [[BufferManager]]
- [[ComputeRangePolicy]]
- [[PFSFBufferManager]]
- [[PFSFEngine]]
- [[PFSFEngineInstance]]
- [[full]]
- [[getOrCreate]]
- [[getOrCreateBuffer]]
- [[onServerTick]]
