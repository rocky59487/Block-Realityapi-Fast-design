---
id: "trouble:sidecar-security"
type: troubleshooting
tags: ["troubleshooting", "sidecar", "rpc", "security", "crash"]
severity: "high"
---

# 🚑 Sidecar RPC 輸入驗證失敗或崩潰

> [!failure] 症狀
> SharedMemoryBridge 或 SidecarBridge 接收到異常資料後 crash。原因：沒有對 RPC payload 做長度、範圍、magic number 驗證。解決：在 BinaryRpcCodec 中加入邊界檢查；對所有不可信輸入假設為惡意資料。參考 SidecarBridgeSecurityTest。

> [!danger] Severity: `high`

> [!tip] 相關資訊
> 🚨 Severity: `high`
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/sidecar/BinaryRpcCodec.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/sidecar/SidecarBridge.java`
>   - `Block Reality/api/src/test/java/com/blockreality/api/sidecar/SidecarBridgeSecurityTest.java`

## 🔗 Related Notes
- [[BinaryRpcCodec]]
- [[SharedMemoryBridge]]
- [[SidecarBridge]]
- [[SidecarBridgeSecurityTest]]
- [[load]]
