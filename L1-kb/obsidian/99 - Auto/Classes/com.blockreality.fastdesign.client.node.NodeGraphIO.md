---
id: "java_fd:com.blockreality.fastdesign.client.node.NodeGraphIO"
type: class
tags: ["java", "fastdesign", "node", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.NodeGraphIO

> [!info] 摘要
> 節點圖序列化/反序列化 — 設計報告 §12.1 N1-6  支援格式： - .brgraph（GZIP 壓縮 JSON） - .json（純文字 JSON，開發用）  JSON 結構： { "version": "1.1", "name": "My Graph", "author": "player", "nodes": [ { "id", "type", "posX", "posY", "inputs": {...}, ... } ], "wires": [ { "from": "nodeId.portName", "to": "nodeId.portName" } ], "groups": [ { "id", "name", "color", "nodeIds": [...] } ] }

## 🔗 Related
- [[NodeGraph]]
- [[NodeGraphIO]]
- [[author]]
- [[color]]
- [[com.blockreality.fastdesign.client.node.NodeGraph]]
- [[from]]
- [[inputs]]
- [[name]]
- [[nodeId]]
- [[nodeIds]]
- [[play]]
- [[posX]]
- [[posY]]
- [[type]]
- [[wire]]
