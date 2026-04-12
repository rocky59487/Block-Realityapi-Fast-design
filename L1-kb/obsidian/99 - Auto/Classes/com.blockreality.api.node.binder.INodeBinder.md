---
id: "java_api:com.blockreality.api.node.binder.INodeBinder"
type: class
tags: ["java", "api", "binder"]
---

# 🧩 com.blockreality.api.node.binder.INodeBinder

> [!info] 摘要
> ★ review-fix ICReM-7: 節點綁定器介面。  API 層只定義介面，具體實作由模組層（fastdesign/architect）提供。 這確保 API 不依賴任何具體節點類型或渲染設定。  綁定器的職責： - pushToSettings: 評估後將節點輸出推送到運行時設定 - pullFromSettings: 初始化時從運行時設定填入節點輸入 - init/cleanup: 生命週期管理

## 🔗 Related
- [[INodeBinder]]
- [[bind]]
- [[cleanup]]
- [[init]]
- [[pull]]
- [[pullFromSettings]]
- [[push]]
- [[pushToSettings]]
