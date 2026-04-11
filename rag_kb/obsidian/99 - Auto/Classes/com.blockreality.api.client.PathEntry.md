---
id: "java_api:com.blockreality.api.client.PathEntry"
type: class
tags: ["java", "api", "client", "client-only"]
---

# 🧩 com.blockreality.api.client.PathEntry

> [!info] 摘要
> 客戶端錨定路徑快取 — 想法.docx AnchorPathVisualizer  儲存從伺服器同步的錨定 BFS 路徑數據。 由 AnchorPathRenderer 讀取並渲染為半透明線段。  ★ R6-10 fix: 每條路徑新增 isAnchored 狀態， 有效路徑渲染為綠色、無效路徑渲染為紅色。  線程安全：paths 使用 volatile 引用交換， 讀取端（渲染線程）只讀取 snapshot，不需要鎖。

## 🔗 Related
- [[AnchorPathRenderer]]
- [[Entry]]
- [[PathEntry]]
- [[isAnchor]]
- [[isAnchored]]
