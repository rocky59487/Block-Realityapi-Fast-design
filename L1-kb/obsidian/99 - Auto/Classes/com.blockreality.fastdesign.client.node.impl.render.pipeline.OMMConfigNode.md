---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.OMMConfigNode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.OMMConfigNode

> [!info] 摘要
> Opacity Micromap（OMM）配置節點（Phase 7）。  <p>控制 VK_EXT_opacity_micromap 的啟用狀態與 subdivision level。 OMM 可讓 GPU 在 BVH 遍歷時跳過半透明材料的 any-hit shader， 通常可提升 RT shadow/AO 效能 20-40%（取決於場景透明物比例）。  <h3>端口說明</h3> <ul> <li>{@code enableOMM}         — 啟用 OMM（需 Ada+ GPU + VK_EXT_opacity_micromap）</li> <li>{@code subdivisionLevel}  — OMM 細分等級（0-4；越高精度越高、記憶體越大）</li> </ul>  @see BROpacityMicromap

> [!tip] 資訊
> 🔼 Extends: [[BRNode]]

## 🔗 Related
- [[BRNode]]
- [[BROpacityMicromap]]
- [[Config]]
- [[OMMConfigNode]]
- [[line]]
- [[render]]
- [[shadow]]
