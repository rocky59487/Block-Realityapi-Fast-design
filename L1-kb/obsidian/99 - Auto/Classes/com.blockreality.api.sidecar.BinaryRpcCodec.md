---
id: "java_api:com.blockreality.api.sidecar.BinaryRpcCodec"
type: class
tags: ["java", "api", "sidecar"]
---

# 🧩 com.blockreality.api.sidecar.BinaryRpcCodec

> [!info] 摘要
> Sidecar 二進制 RPC 編解碼器 — D-4a  在現有 JSON-RPC 2.0 語意之上，提供 length-prefixed 二進制框架， 減少 JSON 解析開銷與 UTF-8 編碼成本。  線路格式： [4 bytes: payload length (big-endian)] [N bytes: payload]  Payload 格式（兩種模式）： - JSON 模式 (magic=0x00): payload = UTF-8 JSON 字串（向後相容） - MessagePack 模式 (magic=0x01): payload = msgpack(header) + RLE 壓縮體素數據  協議握手： 啟動後 Sidecar 發送 capabilities JSON，Java 端回應選擇的編碼模式。 若 Sidecar 不支援二進制，自動降級為 JSON 模式。 

> [!tip] 資訊
> 🔌 Implements: Closeable

## 🔗 Related
- [[BinaryRpcCodec]]
- [[load]]
