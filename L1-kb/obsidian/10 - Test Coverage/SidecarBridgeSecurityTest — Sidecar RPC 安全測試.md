---
id: "test:SidecarBridgeSecurityTest"
type: test_coverage
tags: ["test", "sidecar", "security", "rpc", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.sidecar.SidecarBridgeSecurityTest'"
---

# 🧪 SidecarBridgeSecurityTest — Sidecar RPC 安全測試

## 📝 適用場景
測試 BinaryRpcCodec 與 SidecarBridge 對異常輸入的邊界檢查與防禦。修改 sidecar 通訊協議或 RPC payload 格式時必須執行。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.sidecar.SidecarBridgeSecurityTest'`
> 📎 Related Source:
>   - [[SidecarBridge]]
>   - [[BinaryRpcCodec]]

## 🔗 Related Notes
- [[BinaryRpcCodec]]
- [[SidecarBridge]]
- [[SidecarBridgeSecurityTest]]
- [[load]]
