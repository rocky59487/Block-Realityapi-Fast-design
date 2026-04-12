---
id: "java_fd:com.blockreality.fastdesign.client.ClientSelectionHolder"
type: class
tags: ["java", "fastdesign", "client", "client-only"]
---

# 🧩 com.blockreality.fastdesign.client.ClientSelectionHolder

> [!info] 摘要
> Client-side 選取狀態 — v3fix §4 / 開發手冊 §11 管理客戶端目前方塊選取範圍(pos1, pos2)並計算體積大小。  <p>範例套用: <pre> // 更新選取範圍 ClientSelectionHolder.update(pos1, pos2);  // 獲取選取資訊並讀取大小 ClientSelectionHolder.SelectionData data = ClientSelectionHolder.get(); if (data != null) { int vol = data.volume(); } </pre>

## 🔗 Related
- [[ClientSelectionHolder]]
- [[Holder]]
- [[SelectionData]]
- [[update]]
- [[volume]]
