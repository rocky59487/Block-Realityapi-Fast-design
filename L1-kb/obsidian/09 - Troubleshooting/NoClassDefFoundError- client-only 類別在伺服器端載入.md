---
id: "trouble:noclassdeffound-client"
type: troubleshooting
tags: ["troubleshooting", "crash", "client-only", "NoClassDefFoundError"]
severity: "critical"
---

# 🚑 NoClassDefFoundError: client-only 類別在伺服器端載入

> [!failure] 症狀
> 專用伺服器啟動時報 NoClassDefFoundError，指向 client/ 下的類別。原因：某個 server-side 類別（通常是 network packet 或 event handler）直接 import 了 client-only 類別，或缺少 @OnlyIn(Dist.CLIENT)。解決：1. 檢查 stack trace 中最先出現的 import 路徑。2. 將該 import 改為 FQN 字串延遲載入，或將邏輯移到 client proxy。3. 執行 python fix_imports.py 自動修復 network 封包。4. 執行 python fix_only_in_client.py 自動補上 @OnlyIn。

> [!danger] Severity: `critical`

> [!tip] 相關資訊
> 🚨 Severity: `critical`
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/network/`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/`

## 🔗 Related Notes
- [[CLIENT)]]
- [[handle]]
- [[ports]]
