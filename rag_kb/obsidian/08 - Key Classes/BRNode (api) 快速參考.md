---
id: "keyclass:BRNode-api"
type: key_class
tags: ["key-class", "node", "core"]
fqn: "com.blockreality.api.node.BRNode"
thread_safety: "evaluate called sequentially by scheduler"
---

# 🧩 BRNode (api) 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.node.BRNode`
> **Thread Safety**: evaluate called sequentially by scheduler

## 📋 職責
節點圖的抽象基類，定義 InputPort / OutputPort 與 evaluate() 接口。api 層的 BRNode 是純資料結構，不含渲染或 Minecraft 客戶端邏輯。線程安全：evaluate() 在單一執行緒中依拓撲序呼叫。主要方法：evaluate()、addPort(PortType, String)。修改注意：變更 port 定義會影響 NodeGraphIO 序列化相容性。

## 🔗 Related Notes
- [[BRNode]]
- [[InputPort]]
- [[NodeGraph]]
- [[NodeGraphIO]]
- [[OutputPort]]
- [[PortType]]
- [[String]]
- [[Type]]
- [[evaluate]]
- [[ring]]
