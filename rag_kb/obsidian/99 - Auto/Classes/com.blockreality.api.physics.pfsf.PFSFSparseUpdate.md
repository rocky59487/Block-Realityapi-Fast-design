---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFSparseUpdate"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFSparseUpdate

> [!info] 摘要
> PFSF 稀疏增量更新系統 — 解決 PCIe 頻寬瓶頸。  <h2>問題</h2> 傳統做法：方塊變更 → 重新上傳整個 source/conductivity/type 陣列（100K 方塊 = 37MB）。 每秒放置/破壞數十個方塊時，PCIe 頻寬會被塞爆。  <h2>解決方案：三層稀疏策略</h2> <ol> <li><b>Dirty Voxel Tracking</b>：只追蹤實際變更的體素索引（通常 1~20 個/tick）</li> <li><b>Compact Update Buffer</b>：將變更打包成小型上傳 buffer（index + value pairs）</li> <li><b>GPU Scatter Shader</b>：GPU 端將打包的變更散布到大陣列中（零 CPU 碰觸大陣列）</li> </ol>  <h2>頻寬對比</h2> <pre> 舊：

## 🔗 Related
- [[PFSFSparseUpdate]]
- [[index]]
- [[tick]]
- [[type]]
