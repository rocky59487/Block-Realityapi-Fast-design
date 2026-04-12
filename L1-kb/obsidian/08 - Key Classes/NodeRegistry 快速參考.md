---
id: "keyclass:NodeRegistry"
type: key_class
tags: ["key-class", "node", "fastdesign", "registry"]
fqn: "com.blockreality.fastdesign.client.node.NodeRegistry"
thread_safety: "read-only after init"
fix_script: "fix_registry.py"
---

# 🧩 NodeRegistry 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.fastdesign.client.node.NodeRegistry`
> **Thread Safety**: read-only after init

## 📋 職責
靜態註冊所有 FastDesign 節點類別，提供工廠方法、搜尋（中英文模糊匹配）、按類別分組。新增節點後若忘記註冊，會導致節點圖載入失敗。線程安全：初始化後只讀。主要方法：register(Supplier<BRNode>)、create(String id)、search(String query)。修改注意：可用 fix_registry.py 自動掃描並補上未註冊節點。

> [!tip] Metadata
> 🛠️ Fix Script: `fix_registry.py`

## 🔗 Related Notes
- [[BRNode]]
- [[NodeRegistry]]
- [[String]]
- [[create]]
- [[query]]
- [[register]]
- [[ring]]
- [[search]]
