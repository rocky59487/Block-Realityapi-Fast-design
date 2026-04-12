---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.ReSTIRConfigNode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.ReSTIRConfigNode

> [!info] 摘要
> ReSTIR DI / GI 配置節點（Phase 7）。  <p>透過節點編輯器介面調整 ReSTIR DI（直接光照）與 ReSTIR GI（間接光照）的 採樣與重用參數，即時生效。  <h3>端口說明</h3> <ul> <li>{@code enableDI}       — 啟用 ReSTIR DI（Blackwell 直接光照採樣）</li> <li>{@code enableGI}       — 啟用 ReSTIR GI（次射線間接照明）</li> <li>{@code initialCands}   — 初始候選採樣數（8-64，越高越準確）</li> <li>{@code temporalMaxM}   — DI 時域 M-cap（10-30；GI 使用固定 10）</li> <li>{@code spatialSamples} — DI 空間鄰居數（1-4）</li>

> [!tip] 資訊
> 🔼 Extends: [[BRNode]]

## 🔗 Related
- [[BRNode]]
- [[Config]]
- [[ReSTIRConfigNode]]
- [[init]]
- [[line]]
- [[render]]
