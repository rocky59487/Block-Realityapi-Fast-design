---
id: "keyclass:BRNetwork"
type: key_class
tags: ["key-class", "network", "packet"]
fqn: "com.blockreality.api.network.BRNetwork"
thread_safety: "SimpleChannel thread-safe"
---

# 🧩 BRNetwork / FdNetwork 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.network.BRNetwork`
> **Thread Safety**: SimpleChannel thread-safe

## 📋 職責
統一封包註冊與發送入口。api 層為 BRNetwork，fastdesign 層為 FdNetwork。所有自訂封包（StressSyncPacket、CollapseEffectPacket、FdSelectionSyncPacket 等）都透過此處註冊到 SimpleChannel。線程安全：Network 底層是 thread-safe 的，但封包 handler 的邏輯需注意 side。修改注意：封包類別絕對不能直接 import client-only 類別。

> [!warning] WARNING
> packet classes must not import client-only classes directly

> [!tip] Metadata
> ⚠️ **WARNING**: packet classes must not import client-only classes directly

## 🔗 Related Notes
- [[BRNetwork]]
- [[CollapseEffect]]
- [[CollapseEffectPacket]]
- [[FdNetwork]]
- [[FdSelectionSyncPacket]]
- [[StressSyncPacket]]
- [[handle]]
- [[read]]
- [[safe]]
