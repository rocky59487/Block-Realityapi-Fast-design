---
id: "pattern:new-node"
type: pattern
tags: ["pattern", "node", "fastdesign", "howto"]
---

# 🧩 如何新增一個 FastDesign 節點

## 🪜 步驟
- 1. 在 fastdesign/client/node/impl/<category>/ 下建立新類別，繼承 BRNode。
- 2. 在建構函數中定義 InputPort / OutputPort 與對應的 PortType。
- 3. 實作 evaluate() 方法，處理輸入並產生輸出。
- 4. 在 NodeRegistry 中註冊。
- 5. 如需要 runtime 綁定，實作 IBinder<T>。

> [!example] 範例類別: [[ConcreteMaterialNode]]

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/impl/material/base/ConcreteMaterialNode.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/BRNode.java`

## 🔗 Related Notes
- [[BRNode]]
- [[ConcreteMaterialNode]]
- [[IBinder]]
- [[InputPort]]
- [[NodeRegistry]]
- [[OutputPort]]
- [[PortType]]
- [[Type]]
- [[category]]
- [[evaluate]]
