---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.DLSSMode"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.DLSSMode

> [!info] 摘要
> DLSS 4 超解析度 + Frame Generation 配置節點（Phase 7）。  <p>控制 DLSS 4 SR 品質模式與 MFG 幀生成選項。 若 DLSS SDK 未載入，節點保持 passthrough 狀態並記錄 warning。  <h3>端口說明</h3> <ul> <li>{@code enableDLSS}   — 啟用 DLSS SR（超解析度）</li> <li>{@code dlssMode}     — DLSS 品質模式（0-4）</li> <li>{@code enableFG}     — 啟用 Frame Generation（Ada=DLSS3 FG，Blackwell=DLSS4 MFG）</li> </ul>  @see BRDLSS4Manager @see BRRTSettings#isEnableDLSS()

## 🔗 Related
- [[BRDLSS4Manager]]
- [[BRRTSettings]]
- [[BRRTSettings#isEnableDLSS]]
- [[DLSSMode]]
- [[isEnableDLSS]]
- [[line]]
- [[render]]
