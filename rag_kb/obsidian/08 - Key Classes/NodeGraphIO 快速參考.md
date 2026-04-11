---
id: "keyclass:NodeGraphIO"
type: key_class
tags: ["key-class", "node", "io", "serialization"]
fqn: "com.blockreality.api.node.NodeGraphIO"
thread_safety: "CLIENT save/load only"
---

# 🧩 NodeGraphIO 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.node.NodeGraphIO`
> **Thread Safety**: CLIENT save/load only

## 📋 職責
節點圖的序列化與反序列化，儲存格式為 NBT。負責儲存節點位置、連線、各節點的內部狀態。線程安全：通常只在客戶端存檔時呼叫。主要方法：write(NodeGraph, CompoundTag)、read(CompoundTag)。修改注意：PortType 不匹配會導致連線靜默失敗（載入後 evaluate 時跳過）。

> [!warning] WARNING
> PortType mismatch causes silent wire failures

> [!tip] Metadata
> ⚠️ **WARNING**: PortType mismatch causes silent wire failures

## 🔗 Related Notes
- [[CompoundTag]]
- [[NodeGraph]]
- [[NodeGraphIO]]
- [[PortType]]
- [[Type]]
- [[evaluate]]
- [[read]]
- [[write]]
- [[內部狀態]]
