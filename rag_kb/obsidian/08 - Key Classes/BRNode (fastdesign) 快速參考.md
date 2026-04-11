---
id: "keyclass:BRNode-fastdesign"
type: key_class
tags: ["key-class", "node", "fastdesign", "client-only"]
fqn: "com.blockreality.fastdesign.client.node.BRNode"
thread_safety: "CLIENT only"
---

# 🧩 BRNode (fastdesign) 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.fastdesign.client.node.BRNode`
> **Thread Safety**: CLIENT only

## 📋 職責
fastdesign 層的 BRNode 繼承 api 層 BRNode，增加了 UI 相關屬性（顏色、圖示、分類、內嵌控制項）。evaluate() 的實作通常會將結果綁定到遊戲對象（Material、RenderConfig）。線程安全：僅在客戶端執行。修改注意：所有 fastdesign client 類別必須標註 @OnlyIn(Dist.CLIENT)。

> [!warning] WARNING
> 必須標註 @OnlyIn(Dist.CLIENT)

> [!tip] Metadata
> ⚠️ **WARNING**: 必須標註 @OnlyIn(Dist.CLIENT)

## 🔗 Related Notes
- [[BRNode]]
- [[CLIENT)]]
- [[Config]]
- [[evaluate]]
