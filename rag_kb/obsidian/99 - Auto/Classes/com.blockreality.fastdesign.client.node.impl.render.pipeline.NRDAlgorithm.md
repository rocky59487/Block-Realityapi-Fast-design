---
id: "java_fd:com.blockreality.fastdesign.client.node.impl.render.pipeline.NRDAlgorithm"
type: class
tags: ["java", "fastdesign", "pipeline", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.impl.render.pipeline.NRDAlgorithm

> [!info] 摘要
> NRD 降噪演算法配置節點（Phase 7）。  <p>選擇 NRD（NVIDIA Real-time Denoisers）使用的演算法與強度參數： <ul> <li><b>ReLAX Diffuse/Specular</b>  — 最適合 ReSTIR GI 漫反射 + 鏡面信號</li> <li><b>ReBLUR</b>                  — 最適合 RTAO / DDGI 低頻輻射信號</li> <li><b>SIGMA Shadow</b>            — 最適合 ReSTIR DI 陰影信號</li> <li><b>SVGF（Fallback）</b>        — NRD JNI 不可用時的備援，無需原生庫</li> </ul>  <h3>端口說明</h3> <ul> <li>{@code algorithm}       — 降噪演算法選擇</

## 🔗 Related
- [[Algorithm]]
- [[NRDAlgorithm]]
- [[line]]
- [[render]]
