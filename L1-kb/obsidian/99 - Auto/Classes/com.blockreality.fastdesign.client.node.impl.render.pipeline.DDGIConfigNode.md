---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.DDGIConfigNode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.DDGIConfigNode

> [!info] 摘要
> DDGI Probe 系統配置節點（Phase 7）。  <p>調整 Dynamic Diffuse GI（Ada 路徑）的 probe 網格密度與更新頻率。  <h3>端口說明</h3> <ul> <li>{@code enableDDGI}    — 啟用 DDGI（僅 Ada+ GPU）</li> <li>{@code probeSpacing}  — Probe 間距（方塊單位，1-32；越小精度越高）</li> <li>{@code updateRatio}   — 每幀更新比例（0.0-1.0；0.25 = 每 4 幀輪轉）</li> <li>{@code irradBias}     — 自遮擋偏移係數（0.1-1.0，建議 0.3）</li> </ul>  @see BRRTSettings#isEnableDDGI() @see com.blockreality.api.

> [!tip] 資訊
> 🔼 Extends: [[BRNode]]

## 🔗 Related
- [[BRNode]]
- [[BRRTSettings]]
- [[BRRTSettings#isEnableDDGI]]
- [[Config]]
- [[DDGIConfigNode]]
- [[isEnableDDGI]]
- [[line]]
- [[render]]
- [[update]]
