---
id: "java_api:com.blockreality.api.network.PathEntryData"
type: class
tags: ["java", "api", "network"]
---

# 🧩 com.blockreality.api.network.PathEntryData

> [!info] 摘要
> S→C 錨定路徑同步封包 — 想法.docx AnchorPathVisualizer  將伺服器端的錨定 BFS 路徑同步到客戶端。 客戶端使用 AnchorPathRenderer 以半透明線段渲染路徑。  ★ R6-10 fix: 每條路徑新增 isAnchored 布林值， 讓渲染器區分有效（綠色）和無效（紅色）路徑。  封包格式（v2）： [int: pathCount] repeat pathCount: [boolean: isAnchored] [int: nodeCount] repeat nodeCount: [long: blockPos.asLong()]

## 🔗 Related
- [[AnchorPathRenderer]]
- [[Entry]]
- [[PathEntry]]
- [[PathEntryData]]
- [[isAnchor]]
- [[isAnchored]]
- [[nodeCount]]
