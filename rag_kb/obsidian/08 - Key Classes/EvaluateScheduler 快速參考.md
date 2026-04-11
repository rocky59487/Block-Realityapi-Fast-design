---
id: "keyclass:EvaluateScheduler"
type: key_class
tags: ["key-class", "node", "scheduler"]
fqn: "com.blockreality.api.node.EvaluateScheduler"
thread_safety: "single-threaded evaluation"
---

# 🧩 EvaluateScheduler 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.node.EvaluateScheduler`
> **Thread Safety**: single-threaded evaluation

## 📋 職責
對 NodeGraph 執行拓撲排序（Kahn 算法），並按順序呼叫每個 dirty 節點的 evaluate()。api 層與 fastdesign 層各有一個實作。線程安全：通常單執行緒呼叫，但 fastdesign 版本可能面對 UI 線程與渲染線程的互動。主要方法：evaluateAll()、markDirty(BRNode)。修改注意：環狀依賴會導致 evaluateAll() 拋出異常或沈默跳過。

> [!warning] WARNING
> circular dependencies cause silent skips or exceptions

> [!tip] Metadata
> ⚠️ **WARNING**: circular dependencies cause silent skips or exceptions

## 🔗 Related Notes
- [[BRNode]]
- [[EvaluateScheduler]]
- [[NodeGraph]]
- [[evaluate]]
- [[evaluateAll]]
- [[markDirty]]
