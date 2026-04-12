---
id: "arch:sidecar-bridge"
type: architecture
tags: ["sidecar", "rpc", "native", "bridge"]
---

# 📄 Sidecar RPC 與共享記憶體橋接

## 📖 內容
api/sidecar/ 提供 BinaryRpcCodec、SharedMemoryBridge、SidecarBridge，用於與外部進程（如 Python ML 推論服務）通訊。所有輸入皆須視為不可信資料，做好邊界檢查。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/sidecar/`
>   - `Block Reality/api/src/test/java/com/blockreality/api/sidecar/SidecarBridgeSecurityTest.java`

## 🔗 Related Notes
- [[BinaryRpcCodec]]
- [[SharedMemoryBridge]]
- [[SidecarBridge]]
- [[SidecarBridgeSecurityTest]]
