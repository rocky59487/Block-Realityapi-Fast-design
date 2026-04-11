---
id: "rule:node-port-match"
type: rule
tags: ["rule", "node", "fastdesign"]
---

# 📄 節點 Port 類型匹配

## 📖 內容
NodeGraphIO 序列化 port 類型，類型不匹配會導致連線靜默失敗（不會拋出異常，但 evaluate 時會跳過）。新增節點時務必確認 PortType 與實際資料類型一致。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/node/NodePort.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/node/PortType.java`

## 🔗 Related Notes
- [[NodeGraph]]
- [[NodeGraphIO]]
- [[NodePort]]
- [[PortType]]
- [[Type]]
- [[evaluate]]
